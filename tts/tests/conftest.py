from pathlib import Path

import pytest

from epub_factory import Doc, TocNode, body_of, build_epub, p, paragraphs


def full_docs() -> tuple[list[Doc], list[TocNode]]:
    """Un roman synthétique : pages liminaires, deux parties, trois chapitres, notes."""
    title_page = Doc(
        "titre.xhtml",
        "Titre",
        "<h1>Les Seigneurs de Valcombe</h1><p>Roman</p><p>Jeanne Dupré-Marsan</p>",
    )
    legal = Doc(
        "copyright.xhtml",
        "Copyright",
        "<p>© 2024 Éditions du Vieux Moulin. Tous droits réservés.</p>"
        "<p>ISBN 978-0-00-000000-0. Dépôt légal : mai 2024.</p>",
    )
    dedication = Doc(
        "dedicace.xhtml",
        "Dédicace",
        '<section epub:type="dedication"><p>À ma grand-mère, qui racontait si bien.</p></section>',
    )
    sommaire = Doc(
        "sommaire.xhtml",
        "Sommaire",
        "<h1>Sommaire</h1><ul>"
        '<li><a href="ch1.xhtml">Chapitre I</a></li>'
        '<li><a href="ch2.xhtml">Chapitre II</a></li>'
        '<li><a href="ch3.xhtml">Chapitre III</a></li></ul>',
    )
    part1 = Doc("partie1.xhtml", "Partie 1", "<h1>Première partie</h1><p><em>1572</em></p>")
    ch1_paras = paragraphs(14, 0)
    ch1 = Doc(
        "ch1.xhtml",
        "Chapitre I",
        "<h2 id='c1'>Chapitre I</h2>\n"
        + p(ch1_paras[0] + '<a epub:type="noteref" href="notes.xhtml#n1">1</a>')
        + '<span epub:type="pagebreak" id="pg5" title="5"/>'
        + p("Les Seigneurs de Valcombe")  # en-tête courant, répété
        + p(ch1_paras[1] + '<sup><a href="notes.xhtml#n2">2</a></sup>')
        + p("12")  # numéro de page
        + p("Il se hâta vers la for-\nteresse de Mespech, où cheva&#173;lier et page l'attendaient.")
        + "\n".join(p(t) for t in ch1_paras[2:])
        + "<hr/>"
        + p("Le lendemain, Henri IV entrait dans Paris, et le XVIe siècle finissait mal."),
    )
    ch2 = Doc(
        "ch2.xhtml",
        "Chapitre II",
        "<h2 id='c2'>Chapitre II</h2>\n"
        + "\n".join(
            p(t) + (p("Les Seigneurs de Valcombe") if i % 3 == 2 else "")
            for i, t in enumerate(paragraphs(14, 3))
        ),
    )
    part2 = Doc("partie2.xhtml", "Partie 2", "<h1>Deuxième partie</h1>")
    ch3 = Doc(
        "ch3.xhtml",
        "Chapitre III",
        "<h2 id='c3'>Chapitre III</h2>\n"
        + body_of(14, 6)
        + p("Les Seigneurs de Valcombe")
        + p("Les Seigneurs de Valcombe"),
    )
    notes = Doc(
        "notes.xhtml",
        "Notes",
        '<section epub:type="endnotes"><h1>Notes</h1><ol>'
        '<li id="n1" epub:type="endnote"><p>Mespech : domaine imaginaire de ce récit. '
        '<a href="ch1.xhtml#r1">↩</a></p></li>'
        '<li id="n2" epub:type="endnote"><p>Hortense : prénom d\'une servante inventée.</p></li>'
        "</ol></section>",
    )
    toc: list[TocNode] = [
        ("Page de titre", "titre.xhtml", []),
        (
            "Première partie",
            "partie1.xhtml",
            [("Chapitre I", "ch1.xhtml#c1", []), ("Chapitre II", "ch2.xhtml#c2", [])],
        ),
        ("Deuxième partie", "partie2.xhtml", [("Chapitre III", "ch3.xhtml#c3", [])]),
        ("Notes", "notes.xhtml", []),
    ]
    docs = [title_page, legal, dedication, sommaire, part1, ch1, ch2, part2, ch3, notes]
    return docs, toc


@pytest.fixture
def full_epub(tmp_path: Path) -> Path:
    docs, toc = full_docs()
    return build_epub(
        tmp_path / "valcombe.epub", docs, toc, version=2, cover=True, series=("Valcombe", "1")
    )


@pytest.fixture
def full_epub3(tmp_path: Path) -> Path:
    docs, toc = full_docs()
    return build_epub(tmp_path / "valcombe3.epub", docs, toc, version=3, cover=True)
