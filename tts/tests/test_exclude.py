from livres_tts.epub.chapters import Chapter
from livres_tts.epub.prepare import select_excluded


def _chapters(*titles: str) -> list[Chapter]:
    return [Chapter(index=i, title=t) for i, t in enumerate(titles, start=1)]


def test_exclude_by_number_and_title_ignoring_case_and_accents():
    chs = _chapters(
        "Préface", "Chapitre premier", "Notes de l’auteur", "Mots occitans UTILISÉS dans ce roman"
    )
    chosen = select_excluded(chs, ["1", "utilises dans ce roman", "notes de l"])
    assert [c.title for c in chosen] == [
        "Préface",
        "Mots occitans UTILISÉS dans ce roman",
        "Notes de l’auteur",
    ]


def test_exclude_nothing_and_unknown():
    chs = _chapters("Un", "Deux")
    assert select_excluded(chs, []) == []
    assert select_excluded(chs, ["  ", "99", "absent"]) == []
