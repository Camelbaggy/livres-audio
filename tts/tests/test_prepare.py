import json

import pytest

from epub_factory import Doc, add_encryption, body_of, build_epub
from livres_tts import cli
from livres_tts.epub.prepare import PrepareOptions, estimate_cost, prepare, safe_filename
from livres_tts.text.normalize import normalize


def test_safe_filename():
    assert safe_filename('Chapitre I : "Le gué" ?') == "Chapitre I Le gué"
    assert safe_filename("...") == "Sans titre"
    assert len(safe_filename("x" * 300)) == 80


def test_estimate_cost_uses_free_tier():
    small = estimate_cost(500_000)
    assert small["cout_avec_palier"] == 0
    big = estimate_cost(2_000_000)
    assert big["cout_avec_palier"] == pytest.approx(big["cout_sans_palier"] / 2, abs=0.01)


def test_prepare_end_to_end(full_epub, tmp_path):
    out = tmp_path / "projet"
    result = prepare(full_epub, out, PrepareOptions())
    names = sorted(p.name for p in (out / "chapitres").iterdir())
    assert names == ["01 - Chapitre I.txt", "02 - Chapitre II.txt", "03 - Chapitre III.txt"]
    assert sorted(p.name for p in (out / "chapitres-bruts").iterdir()) == names
    assert (out / "couverture.jpg").read_bytes().startswith(b"\xff\xd8\xff")
    assert (out / "rapport.html").exists() and (out / "projet.json").exists()

    project = json.loads((out / "projet.json").read_text("utf-8"))
    assert project["schemaVersion"] == 1
    assert project["livre"]["titre"] == "Les Seigneurs de Valcombe"
    assert project["livre"]["serie"] == "Valcombe" and project["livre"]["tome"] == "1"
    assert project["chapitrage"] == {"source": "toc", "niveau": 2}
    assert [c["titre"] for c in project["chapitres"]] == [
        "Chapitre I",
        "Chapitre II",
        "Chapitre III",
    ]
    assert project["chapitres"][0]["titre_lu"] == "Chapitre un"
    assert project["chapitres"][0]["fichier"] == "chapitres/01 - Chapitre I.txt"
    assert project["statistiques"]["caracteres"] == result.total_chars > 10_000
    assert project["source"]["sha256"]

    text = (out / "chapitres" / "01 - Chapitre I.txt").read_text("utf-8")
    lines = text.splitlines()
    assert lines[0] == "Première partie."  # titre : point final ajouté
    assert "Chapitre un." in lines
    assert "forteresse de Mespech" in text and "chevalier" in text
    assert "Henri quatre entrait dans Paris" in text and "seizième siècle" in text
    assert "* * *" in lines  # séparateur de scène
    assert "14 mars" not in text and "quatorze mars mille cinq cent soixante et onze" in text
    for retire in ("Les Seigneurs de Valcombe", "Mespech : domaine", "­"):
        assert retire not in text
    assert not any(line.strip() == "12" for line in lines)
    assert all(line for line in lines)  # un paragraphe par ligne, pas de ligne vide
    assert all(normalize(line) == line for line in lines)  # texte final déjà normalisé

    raw_text = (out / "chapitres-bruts" / "01 - Chapitre I.txt").read_text("utf-8")
    assert "Henri IV" in raw_text and "quatre" not in raw_text.split("Henri IV")[1][:10]

    report = (out / "rapport.html").read_text("utf-8")
    for expected in ("Chapitre I", "appel de note", "numéro de page", "Avant / après"):
        assert expected in report
    assert "Henri IV" in report and "Henri quatre" in report
    assert result.removals_by_kind["page de notes"] == 1
    assert result.removals_by_kind["appel de note"] == 2


def test_prepare_keep_notes_reads_notes_at_chapter_end(full_epub, tmp_path):
    out = tmp_path / "p"
    prepare(full_epub, out, PrepareOptions(keep_notes=True))
    lines = (out / "chapitres" / "01 - Chapitre I.txt").read_text("utf-8").splitlines()
    notes = [line for line in lines if line.startswith("Note ")]
    assert notes == [
        "Note un: Mespech: domaine imaginaire de ce récit.",
        "Note deux: Hortense: prénom d’une servante inventée.",
    ]
    assert lines[-2:] == notes


def test_prepare_level_option_and_dedication(full_epub, tmp_path):
    out = tmp_path / "p"
    result = prepare(full_epub, out, PrepareOptions(chapter_level=1, remove_dedications=True))
    assert [c["titre"] for c in result.chapters] == ["Première partie", "Deuxième partie"]
    assert result.removals_by_kind.get("dédicace") == 1


def test_prepare_epub3(full_epub3, tmp_path):
    result = prepare(full_epub3, tmp_path / "p3")
    assert [c["titre"] for c in result.chapters] == ["Chapitre I", "Chapitre II", "Chapitre III"]


def test_prepare_is_repeatable_and_replaces_chapters(full_epub, tmp_path):
    out = tmp_path / "p"
    prepare(full_epub, out)
    (out / "chapitres" / "99 - ancien.txt").write_text("x", "utf-8")
    prepare(full_epub, out, PrepareOptions(chapter_level=1))
    assert not (out / "chapitres" / "99 - ancien.txt").exists()
    assert len(list((out / "chapitres").iterdir())) == 2


def test_cli_prepare(full_epub, tmp_path, capsys):
    out = tmp_path / "cli"
    assert cli.main(["prepare", str(full_epub), "--sortie", str(out)]) == 0
    captured = capsys.readouterr()
    assert "3 chapitres" in captured.out
    assert "palier gratuit" in captured.out and "Google Chirp 3 HD" in captured.out
    assert (out / "projet.json").exists()


def test_cli_prepare_refuses_drm(tmp_path, capsys):
    path = build_epub(
        tmp_path / "drm.epub", [Doc("ch1.xhtml", "C", body_of(3))], [("C", "ch1.xhtml", [])]
    )
    add_encryption(path, "http://www.w3.org/2001/04/xmlenc#aes128-cbc")
    assert cli.main(["prepare", str(path), "--sortie", str(tmp_path / "o")]) == 2
    assert "DRM" in capsys.readouterr().err
    assert not (tmp_path / "o").exists()


def test_cli_prepare_missing_file(tmp_path, capsys):
    assert cli.main(["prepare", str(tmp_path / "absent.epub")]) == 2
    assert "Erreur" in capsys.readouterr().err


def test_report_escapes_html(tmp_path):
    docs = [
        Doc("a.xhtml", "A", "<h1>Un &lt;b&gt;</h1>" + body_of(14, 1)),
        Doc("b.xhtml", "B", "<h1>Deux</h1>" + body_of(14, 2)),
    ]
    path = build_epub(
        tmp_path / "x.epub", docs, [("Un <b>", "a.xhtml", []), ("Deux", "b.xhtml", [])],
        title="Titre <script>alert(1)</script>",
    )  # fmt: skip
    out = tmp_path / "o"
    prepare(path, out)
    report = (out / "rapport.html").read_text("utf-8")
    assert "<script>" not in report and "&lt;script&gt;" in report
