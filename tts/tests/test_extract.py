import pytest

from epub_factory import Doc, add_encryption, body_of, build_epub, rewrite_zip
from livres_tts.epub.extract import DrmError, EpubError, load_epub


def test_epub2_metadata_spine_toc_cover(full_epub):
    book = load_epub(full_epub)
    assert book.title == "Les Seigneurs de Valcombe"
    assert book.authors == ["Jeanne Dupré-Marsan"]
    assert book.language == "fr"
    assert book.year == 2024
    assert (book.series, book.series_index) == ("Valcombe", "1")
    names = [d.path for d in book.spine]
    assert names[:3] == ["cover.xhtml", "titre.xhtml", "copyright.xhtml"]
    assert names.index("ch1.xhtml") < names.index("ch2.xhtml") < names.index("ch3.xhtml")
    levels = [(e.title, e.level, e.path, e.fragment) for e in book.toc]
    assert ("Première partie", 1, "partie1.xhtml", None) in levels
    assert ("Chapitre I", 2, "ch1.xhtml", "c1") in levels
    assert ("Chapitre III", 2, "ch3.xhtml", "c3") in levels
    assert book.cover is not None and book.cover[1] == "jpg"
    assert book.cover[0].startswith(b"\xff\xd8\xff")


def test_epub3_nav_toc(full_epub3):
    book = load_epub(full_epub3)
    assert [e.title for e in book.toc] == [
        "Page de titre",
        "Première partie",
        "Chapitre I",
        "Chapitre II",
        "Deuxième partie",
        "Chapitre III",
        "Notes",
    ]
    assert [e.level for e in book.toc] == [1, 1, 2, 2, 1, 2, 1]


def test_non_french_language_warns(tmp_path):
    path = build_epub(
        tmp_path / "en.epub", [Doc("a.xhtml", "A", body_of(3))], [("A", "a.xhtml", [])],
        language="en",
    )  # fmt: skip
    assert any("français" in w for w in load_epub(path).warnings)


def test_drm_encryption_refused(tmp_path):
    path = build_epub(tmp_path / "x.epub", [Doc("ch1.xhtml", "C", body_of(3))], [("C", "ch1.xhtml", [])])
    add_encryption(path, "http://www.w3.org/2001/04/xmlenc#aes128-cbc")
    with pytest.raises(DrmError, match="DRM"):
        load_epub(path)


def test_adobe_rights_refused(tmp_path):
    path = build_epub(tmp_path / "x.epub", [Doc("ch1.xhtml", "C", body_of(3))], [("C", "ch1.xhtml", [])])
    rewrite_zip(path, {}, {"META-INF/rights.xml": b"<rights/>"})
    with pytest.raises(DrmError, match="rights.xml"):
        load_epub(path)


def test_font_obfuscation_is_not_drm(tmp_path):
    path = build_epub(tmp_path / "x.epub", [Doc("ch1.xhtml", "C", body_of(3))], [("C", "ch1.xhtml", [])])
    add_encryption(path, "http://www.idpf.org/2008/embedding", uri="EPUB/fonts/a.otf")
    assert load_epub(path).title


def test_not_an_epub(tmp_path):
    bad = tmp_path / "bad.epub"
    bad.write_text("pas une archive")
    with pytest.raises(EpubError):
        load_epub(bad)
