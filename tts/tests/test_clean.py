from livres_tts.epub import clean
from livres_tts.epub.clean import CleanOptions, NoteIndex


def html(body: str) -> bytes:
    return (
        '<?xml version="1.0" encoding="utf-8"?><!DOCTYPE html>'
        '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">'
        f"<head><title>t</title></head><body>{body}</body></html>"
    ).encode()


def run(body: str, notes: NoteIndex | None = None, options: CleanOptions | None = None):
    return clean.clean_document("a.xhtml", html(body), notes or NoteIndex(), options)


def kinds(result) -> list[str]:
    return [r.kind for r in result.removals]


LONG = "Un paragraphe assez long pour ne pas ressembler à une mention légale. " * 3


# --- fonctions pures --------------------------------------------------------------


def test_page_number_detection():
    assert clean.is_page_number("12")
    assert not clean.is_page_number("1572")  # une année, pas un numéro de page
    assert clean.is_page_number(" - 143 - ")
    assert not clean.is_page_number("12 hommes")
    assert not clean.is_page_number("1572 ans plus tard")
    assert not clean.is_page_number("")


def test_legal_notice_detection():
    assert clean.is_legal_notice("© 2024 Éditions du Vieux Moulin")
    assert clean.is_legal_notice("ISBN 978-0-00-000000-0")
    assert clean.is_legal_notice("Tous droits réservés.")
    assert not clean.is_legal_notice("Il lui rendit son copyright " + "x" * 600)
    assert not clean.is_legal_notice("Il pleuvait sur Mirecourt.")


def test_join_lines_hyphenation_and_dialogue():
    paras, joined = clean.join_lines("Il se hâta vers la for-\nteresse de\nMespech")
    assert paras == ["Il se hâta vers la forteresse de Mespech"]
    assert joined == ["for-/teresse"]
    paras, _ = clean.join_lines("Il dit :\n— Bonjour.\n— Bonsoir.")
    assert paras == ["Il dit :", "— Bonjour.", "— Bonsoir."]
    # mot composé coupé : on ne recolle pas
    paras, joined = clean.join_lines("Peut-\nêtre demain")
    assert paras == ["Peut-être demain"]
    assert joined == []
    # soft hyphen supprimé
    assert clean.join_lines("tou­jours")[0] == ["toujours"]


def test_running_head_keys():
    texts = ["Les Seigneurs de Valcombe"] * 6 + ["— Oui."] * 9 + ["* * *"] * 7
    texts += [f"Valcombe {i}" for i in range(5)] + ["Il pleuvait."] * 8
    keys = clean.running_head_keys(texts)
    assert "les seigneurs de valcombe" in keys
    assert "valcombe" in keys  # numéro de page ignoré dans la clé
    assert not any("oui" in k for k in keys)  # réplique : jamais
    assert "" not in keys and "* * *" not in keys
    assert "il pleuvait." not in keys  # ponctuation finale : une vraie phrase


# --- notes --------------------------------------------------------------------------


def test_noteref_and_note_body_removed():
    notes = NoteIndex(targets={"n1"}, bodies={"n1": "Une note."})
    res = run(
        f'<p>{LONG}<a epub:type="noteref" href="n.xhtml#n1">1</a></p>'
        '<aside epub:type="footnote" id="n1"><p>1 Une note.</p></aside>',
        notes,
    )
    assert [b.text for b in res.blocks] == [LONG.strip()]
    assert set(kinds(res)) == {clean.R_NOTE_CALL, clean.R_NOTE_BODY}
    assert res.blocks[0].notes == ["Une note."]


def test_heuristic_note_link_in_sup():
    notes = NoteIndex(targets={"n2"}, bodies={"n2": "Autre note."})
    res = run(f'<p>{LONG}<sup><a href="notes.xhtml#n2">2</a></sup> suite.</p>', notes)
    assert res.blocks[0].text.endswith("suite.")
    assert "2" not in res.blocks[0].text.replace(LONG.strip(), "")
    assert clean.R_NOTE_CALL in kinds(res)


def test_isolated_numeric_superscript_removed_but_not_math():
    res = run(f"<p>{LONG}<sup>3</sup> et 10<sup>3</sup> m.</p>")
    assert res.blocks[0].text.endswith("et 10 puissance 3 m.")
    assert kinds(res).count(clean.R_SUP) == 1


def test_note_body_in_anchor_inside_list_item():
    notes = NoteIndex(targets={"fn1"}, bodies={"fn1": "x"})
    res = run(f'<p>{LONG}</p><ol><li><a id="fn1" href="#r">1</a> Texte de la note</li></ol>', notes)
    assert [b.text for b in res.blocks] == [LONG.strip()]
    assert clean.R_NOTE_BODY in kinds(res)


def test_collect_notes_index():
    docs = [
        ("a.xhtml", html(f'<p>{LONG}<a epub:type="noteref" href="n.xhtml#n1">1</a></p>')),
        ("n.xhtml", html('<ol><li id="n1"><p>3. Texte de la note. ↩</p></li></ol>')),
    ]
    index = clean.collect_notes(docs, CleanOptions())
    assert index.targets == {"n1"}
    assert index.bodies["n1"] == "Texte de la note."


# --- numéros de page, en-têtes, pages entières -----------------------------------------


def test_pagebreak_and_page_numbers():
    res = run(
        f'<p>{LONG}<span epub:type="pagebreak" id="p1" title="1"/></p><p>42</p>'
        f'<div class="pagenum">43</div><p>{LONG}</p>'
    )
    assert len(res.blocks) == 2
    assert kinds(res).count(clean.R_PAGE) == 3


def test_running_heads_dropped_across_documents():
    docs = []
    for i in range(3):
        blocks = [
            clean.Block("p", "Les Seigneurs de Valcombe"),
            clean.Block("p", f"Paragraphe numéro {i} qui reste."),
            clean.Block("p", "Les Seigneurs de Valcombe", anchors=("x",)),
        ]
        d = clean.DocResult(f"{i}.xhtml", blocks)
        d.removals.append(clean.Removal(clean.R_PAGE, d.path, "1", position=2))
        docs.append(d)
    assert clean.drop_running_heads(docs, min_count=5) == 6
    assert [b.text for b in docs[0].blocks] == ["Paragraphe numéro 0 qui reste."]
    assert docs[0].removals[0].position == 1  # recalculée
    assert any(r.kind == clean.R_HEADER for r in docs[0].removals)


def test_whole_pages_removed():
    legal = run("<p>© 2024 Éditions du Vieux Moulin. Tous droits réservés.</p>")
    assert legal.removed_as == clean.R_LEGAL and not legal.blocks
    title = clean.clean_document(
        "OEBPS/titre.xhtml", html("<h1>Les Seigneurs</h1><p>Roman</p>"), NoteIndex()
    )
    assert title.removed_as == clean.R_TITLEPAGE
    toc = run(
        "<h1>Sommaire</h1><ul><li><a href='a'>A</a></li><li><a href='b'>B</a></li>"
        "<li><a href='c'>C</a></li></ul>"
    )
    assert toc.removed_as == clean.R_TOC
    nav = run('<nav epub:type="toc"><ol><li><a href="a">A</a></li></ol></nav>')
    assert nav.removed_as == clean.R_TOC
    empty = run('<div><img src="cover.jpg"/></div>')
    assert empty.removed_as == clean.R_EMPTY


def test_dedication_kept_unless_option():
    body = '<section epub:type="dedication"><p>À ma grand-mère.</p></section>'
    assert run(body).removed_as is None
    assert run(body, options=CleanOptions(remove_dedications=True)).removed_as == clean.R_DEDICATION


def test_notes_document_removed():
    res = run('<section epub:type="endnotes"><ol><li id="n1"><p>Note.</p></li></ol></section>')
    assert res.removed_as == clean.R_NOTES_DOC


# --- structure --------------------------------------------------------------------------


def test_blocks_headings_scene_break_and_anchors():
    res = run(
        f'<a id="top"/><section id="s"><h2 id="c1">Chapitre I</h2><p>{LONG}</p><hr/>'
        f"<div>{LONG}<br/>— Oui.<br/>— Non.</div></section>"
    )
    kinds_ = [(b.kind, b.level) for b in res.blocks]
    assert kinds_[0] == ("h", 2)
    assert res.blocks[0].anchors[:2] == ("top", "s") and "c1" in res.blocks[0].anchors
    assert res.blocks[2].text == "* * *"
    assert [b.text for b in res.blocks[3:]] == [LONG.strip(), "— Oui.", "— Non."]


def test_soft_hyphens_counted():
    res = run(f"<p>{LONG} cheva&#173;lier</p>")
    assert res.blocks[0].text.endswith("chevalier")
    assert clean.R_SOFT in kinds(res)


def test_hyphenation_recorded():
    res = run(f"<p>{LONG} for-\nteresse</p>")
    assert res.blocks[0].text.endswith("forteresse")
    assert clean.R_HYPHEN in kinds(res)
