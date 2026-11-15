from epub_factory import Doc, TocNode, body_of, build_epub, p, paragraphs
from livres_tts.epub import chapters as ch
from livres_tts.epub import clean
from livres_tts.epub.extract import load_epub


def chapters_of(path, **kw):
    book = load_epub(path)
    spine = [d for d in book.spine if d.linear]
    copts = clean.CleanOptions()
    notes = clean.collect_notes([(d.path, d.html) for d in spine], copts)
    docs = [clean.clean_document(d.path, d.html, notes, copts) for d in spine]
    clean.drop_running_heads(docs)
    return ch.build_chapters(docs, book.toc, book.title, **kw), docs


# --- fonctions pures ------------------------------------------------------------------


def test_merge_tiny_and_share():
    assert ch.merge_tiny([100, 5000, 200, 6000]) == [5100, 6200]
    assert ch.merge_tiny([5000, 100]) == [5100]
    assert ch.merge_tiny([100]) == [100]
    minutes = ch.CHARS_PER_SECOND * 60
    assert ch.good_share([10 * minutes, 10 * minutes]) == 1.0
    assert ch.good_share([minutes, 10 * minutes]) == 10 / 11  # 1 min : trop court
    assert ch.good_share([90 * minutes]) == 0.0  # 90 min : trop long
    assert ch.good_share([]) == 0.0


def test_choose_level_prefers_finest_good_level():
    minutes = ch.CHARS_PER_SECOND * 60
    sizes = {1: [40 * minutes] * 3, 2: [10 * minutes] * 12, 3: [minutes] * 120}
    assert ch.choose_level([1, 2, 3], sizes) == 2
    only_coarse = {1: [30 * minutes] * 3, 2: [minutes] * 90}
    assert ch.choose_level([1, 2], only_coarse) == 1
    nothing = {1: [200 * minutes], 2: [100 * minutes] * 2}
    assert ch.choose_level([1, 2], nothing) == 2  # égalité : le plus fin


# --- chapitrage de bout en bout ------------------------------------------------------------


def test_nested_parts_choose_chapter_level(full_epub):
    result, _docs = chapters_of(full_epub)
    assert result.source == "toc" and result.level == 2
    assert [c.title for c in result.chapters] == ["Chapitre I", "Chapitre II", "Chapitre III"]
    # la page « Première partie » (courte) est fondue dans le chapitre suivant
    assert result.chapters[0].blocks[0].text == "Première partie"
    assert result.chapters[0].blocks[1].text == "1572"
    assert result.chapters[2].blocks[0].text == "Deuxième partie"
    # dédicace, titre, copyright, sommaire, notes : hors des chapitres
    texts = " ".join(b.text for c in result.chapters for b in c.blocks)
    for absent in ("grand-mère", "Éditions du Vieux Moulin", "Sommaire", "domaine imaginaire"):
        assert absent not in texts
    assert any(r.kind == clean.R_LEADING for r in result.removals)  # dédicace non référencée


def test_level_one_gives_parts(full_epub):
    result, _ = chapters_of(full_epub, level=1)
    assert [c.title for c in result.chapters] == ["Première partie", "Deuxième partie"]
    assert result.level == 1
    assert len(result.chapters[0].blocks) > 20  # chapitres I et II fondus


def test_unknown_level_warns(full_epub):
    result, _ = chapters_of(full_epub, level=7)
    assert result.level == 2 and any("niveau 7" in w for w in result.warnings)


def test_removals_attributed_to_chapters(full_epub):
    result, docs = chapters_of(full_epub)
    index = {d.path: i for i, d in enumerate(docs)}
    pages = [r for d in docs for r in d.removals if r.kind == clean.R_PAGE]
    assert pages
    for r in pages:
        title = result.chapter_title_at(index[r.source], r.position)
        assert title == "01 - Chapitre I"


def test_several_chapters_in_one_file_via_fragments(tmp_path):
    body = ""
    for i in range(3):
        body += f"<h2 id='c{i}'>Chapitre {i + 1}</h2>" + "".join(p(t) for t in paragraphs(14, i))
    path = build_epub(
        tmp_path / "one.epub",
        [Doc("livre.xhtml", "Livre", body)],
        [(f"Chapitre {i + 1}", f"livre.xhtml#c{i}", []) for i in range(3)],
    )
    result, _ = chapters_of(path)
    assert [c.title for c in result.chapters] == ["Chapitre 1", "Chapitre 2", "Chapitre 3"]
    assert all(c.blocks[0].kind == "h" for c in result.chapters)


def test_fallback_to_headings_without_toc(tmp_path):
    body = ""
    for i in range(3):
        body += f"<h1>Titre {i + 1}</h1>" + "".join(p(t) for t in paragraphs(14, i))
    path = build_epub(tmp_path / "h.epub", [Doc("a.xhtml", "A", body)], [])
    result, _ = chapters_of(path)
    assert result.source == "titres"
    assert [c.title for c in result.chapters] == ["Titre 1", "Titre 2", "Titre 3"]


def test_unusable_toc_falls_back_with_warning(tmp_path):
    docs = [Doc(f"d{i}.xhtml", f"D{i}", f"<h1>Titre {i}</h1>" + body_of(14, i)) for i in range(3)]
    toc: list[TocNode] = [("Fantôme", "absent.xhtml", [])]
    result, _ = chapters_of(build_epub(tmp_path / "t.epub", docs, toc))
    assert result.source == "titres" and len(result.chapters) == 3
    assert any("table des matières" in w for w in result.warnings)


def test_one_chapter_per_document_without_headings(tmp_path):
    docs = [Doc(f"d{i}.xhtml", f"D{i}", body_of(14, i)) for i in range(3)]
    result, _ = chapters_of(build_epub(tmp_path / "d.epub", docs, []))
    assert result.source == "documents" and len(result.chapters) == 3
    assert any("un chapitre par document" in w for w in result.warnings)


def test_unlisted_long_front_matter_becomes_chapter(tmp_path):
    docs = [
        Doc("pre.xhtml", "Préface", "<h1>Préface</h1>" + body_of(8, 0)),
        Doc("a.xhtml", "A", "<h1>Un</h1>" + body_of(14, 1)),
        Doc("b.xhtml", "B", "<h1>Deux</h1>" + body_of(14, 2)),
    ]
    toc: list[TocNode] = [("Un", "a.xhtml", []), ("Deux", "b.xhtml", [])]
    result, _ = chapters_of(build_epub(tmp_path / "p.epub", docs, toc))
    assert [c.title for c in result.chapters] == ["Préface", "Un", "Deux"]


def test_linear_no_documents_skipped(tmp_path):
    docs = [
        Doc("a.xhtml", "A", "<h1>Un</h1>" + body_of(14, 1)),
        Doc("aside.xhtml", "X", "<h1>Hors lecture</h1>" + body_of(14, 2), linear=False),
        Doc("b.xhtml", "B", "<h1>Deux</h1>" + body_of(14, 3)),
    ]
    toc: list[TocNode] = [("Un", "a.xhtml", []), ("Deux", "b.xhtml", [])]
    result, _ = chapters_of(build_epub(tmp_path / "l.epub", docs, toc))
    texts = " ".join(b.text for c in result.chapters for b in c.blocks)
    assert "Hors lecture" not in texts
