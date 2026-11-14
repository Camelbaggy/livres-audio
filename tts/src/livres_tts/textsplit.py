"""Découpage simple d'un texte en morceaux respectant une longueur maximale."""

from __future__ import annotations

import re

_SENTENCE_END = re.compile(r"(?<=[.!?…»])\s+")


def split_sentences(text: str) -> list[str]:
    parts = [p.strip() for p in _SENTENCE_END.split(text.replace("\r", ""))]
    return [p for p in parts if p]


def _hard_split(sentence: str, max_chars: int) -> list[str]:
    out: list[str] = []
    current = ""
    for word in sentence.split(" "):
        if current and len(current) + 1 + len(word) > max_chars:
            out.append(current)
            current = word
        else:
            current = f"{current} {word}".strip()
    if current:
        out.append(current)
    return out


def chunk_text(text: str, max_chars: int | None) -> list[str]:
    """Regroupe les phrases en morceaux de `max_chars` au plus (None = un seul morceau)."""
    text = text.strip()
    if not text:
        return []
    if max_chars is None or len(text) <= max_chars:
        return [text]
    chunks: list[str] = []
    current = ""
    for sentence in split_sentences(text):
        pieces = [sentence] if len(sentence) <= max_chars else _hard_split(sentence, max_chars)
        for piece in pieces:
            if current and len(current) + 1 + len(piece) > max_chars:
                chunks.append(current)
                current = piece
            else:
                current = f"{current} {piece}".strip()
    if current:
        chunks.append(current)
    return chunks
