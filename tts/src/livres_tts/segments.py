"""Découpage d'un chapitre en segments à synthétiser et en silences."""

from __future__ import annotations

import re
from dataclasses import dataclass

from .project import Silences
from .textsplit import _hard_split, split_sentences

SCENE_BREAK = "* * *"
MAX_SENTENCES = 3
_DIALOGUE = re.compile(r"^[—–-]\s")


@dataclass(frozen=True)
class Speech:
    text: str


@dataclass(frozen=True)
class Pause:
    seconds: float


Item = Speech | Pause


def sentences(paragraph: str) -> list[str]:
    """Phrases d'un paragraphe ; un fragment qui commence par une minuscule se recolle."""
    out: list[str] = []
    for part in split_sentences(paragraph):
        if out and part[0].islower():
            out[-1] = f"{out[-1]} {part}"
        else:
            out.append(part)
    return out


def split_paragraph(paragraph: str, max_chars: int | None) -> list[str]:
    """1 à 3 phrases par segment, sous `max_chars`. Une réplique reste entière si elle tient."""
    paragraph = paragraph.strip()
    if not paragraph:
        return []
    fits = max_chars is None or len(paragraph) <= max_chars
    if _DIALOGUE.match(paragraph) and fits:
        return [paragraph]
    out: list[str] = []
    cur: list[str] = []
    for sentence in sentences(paragraph):
        pieces = (
            [sentence]
            if max_chars is None or len(sentence) <= max_chars
            else _hard_split(sentence, max_chars)
        )
        for piece in pieces:
            joined = " ".join([*cur, piece])
            if cur and (
                len(cur) >= MAX_SENTENCES or (max_chars is not None and len(joined) > max_chars)
            ):
                out.append(" ".join(cur))
                cur = [piece]
            else:
                cur.append(piece)
    if cur:
        out.append(" ".join(cur))
    return out


def chapter_items(
    title: str,
    lines: list[str],
    max_chars: int | None,
    sil: Silences,
) -> list[Item]:
    """Suite ordonnée de segments de texte et de silences d'un chapitre (textes finaux)."""
    items: list[Item] = [Pause(sil.debut)]
    if title.strip():
        items.append(Speech(title.strip()))
        items.append(Pause(sil.titre))
    pending_gap = 0.0  # silence à insérer avant le prochain texte
    first = True
    for line in lines:
        if line.strip() == SCENE_BREAK:
            pending_gap = sil.scene
            continue
        segs = split_paragraph(line, max_chars)
        for i, seg in enumerate(segs):
            if not first:
                if i > 0:
                    gap = sil.segment
                else:
                    gap = pending_gap or sil.paragraphe
                items.append(Pause(gap))
            first = False
            items.append(Speech(seg))
        pending_gap = 0.0
    items.append(Pause(sil.fin))
    return _merge_pauses(items)


def _merge_pauses(items: list[Item]) -> list[Item]:
    out: list[Item] = []
    for it in items:
        if isinstance(it, Pause) and out and isinstance(out[-1], Pause):
            out[-1] = Pause(max(out[-1].seconds, it.seconds))
        elif isinstance(it, Pause) and it.seconds <= 0:
            continue
        else:
            out.append(it)
    return out
