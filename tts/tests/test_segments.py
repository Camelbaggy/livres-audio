from __future__ import annotations

from livres_tts.project import Silences
from livres_tts.segments import Pause, Speech, chapter_items, split_paragraph

SIL = Silences()


def texts(items):
    return [i.text for i in items if isinstance(i, Speech)]


def test_groups_up_to_three_sentences():
    p = "Un. Deux. Trois. Quatre. Cinq."
    assert split_paragraph(p, 1000) == ["Un. Deux. Trois.", "Quatre. Cinq."]


def test_max_chars_respected_and_hard_split():
    p = "Une phrase assez longue ici. Une autre phrase tout aussi longue."
    segs = split_paragraph(p, 35)
    assert all(len(s) <= 35 for s in segs) and " ".join(segs) == p
    long = "mot " * 30
    assert all(len(s) <= 40 for s in split_paragraph(long.strip(), 40))


def test_dialogue_line_never_cut_when_it_fits():
    line = "— Un. Deux. Trois. Quatre. Cinq. Six."
    assert split_paragraph(line, 200) == [line]
    cut = split_paragraph(line, 20)
    assert len(cut) > 1 and all(len(s) <= 20 for s in cut)


def test_lowercase_fragment_is_glued():
    assert split_paragraph("Il hésita. puis partit.", 100) == ["Il hésita. puis partit."]


def test_chapter_items_structure_and_silences():
    items = chapter_items(
        "Chapitre un.",
        ["Premier paragraphe.", "* * *", "Second paragraphe.", "Troisième."],
        100,
        SIL,
    )
    assert items[0] == Pause(SIL.debut)
    assert items[1] == Speech("Chapitre un.") and items[2] == Pause(SIL.titre)
    assert items[3] == Speech("Premier paragraphe.")
    assert items[4] == Pause(SIL.scene) and items[5] == Speech("Second paragraphe.")
    assert items[6] == Pause(SIL.paragraphe) and items[7] == Speech("Troisième.")
    assert items[-1] == Pause(SIL.fin)
    assert "* * *" not in texts(items)


def test_same_paragraph_segments_use_segment_gap_and_no_double_pauses():
    items = chapter_items("", ["A. B. C. D."], 1000, SIL)
    assert items == [Pause(SIL.debut), Speech("A. B. C."), Pause(SIL.segment), Speech("D."), Pause(SIL.fin)]
    items = chapter_items("T.", [], 100, SIL)
    assert items == [Pause(SIL.debut), Speech("T."), Pause(max(SIL.titre, SIL.fin))]
