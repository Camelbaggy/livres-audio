"""Lexique de prononciation : réécritures déterministes appliquées au texte final.

Fichiers YAML (`global.yaml` puis `<serie>.yaml`) :

    - mot: Siorac            # mot exact (limites de mot) ; ou « regex: ... »
      dire: Sioraque         # réécriture phonétique en français courant (obligatoire)
      casse: true            # true (défaut) : la casse compte ; false : insensible à la casse
      ipa: sjɔʁak            # facultatif, utilisé seulement si le moteur l'accepte
      commentaire: ...

Application (fonction pure, déterministe) : une seule passe de gauche à droite ; à une même
position, l'occurrence la plus longue gagne, puis l'entrée de série avant l'entrée globale, puis
l'ordre dans le fichier. Une réécriture n'est jamais ré-analysée (pas de cascade). Une entrée de
série de même clé (mot/regex + casse) remplace celle du global.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

import yaml

LEXIQUES_ENV = "LIVRES_TTS_LEXIQUES"


class LexiconError(ValueError):
    pass


@dataclass(frozen=True)
class Entry:
    dire: str
    mot: str | None = None
    regex: str | None = None
    casse: bool = True
    ipa: str | None = None
    commentaire: str = ""

    @property
    def key(self) -> tuple[str, bool]:
        return (self.mot if self.mot is not None else f"re:{self.regex}", self.casse)

    def compile(self) -> re.Pattern[str]:
        flags = 0 if self.casse else re.IGNORECASE
        if self.mot is not None:
            return re.compile(rf"(?<!\w){re.escape(self.mot)}(?!\w)", flags)
        assert self.regex is not None
        return re.compile(self.regex, flags)

    @property
    def length(self) -> int:
        return len(self.mot if self.mot is not None else self.regex or "")


def parse_entries(data: object, source: str = "lexique") -> list[Entry]:
    if data is None:
        return []
    if not isinstance(data, list):
        raise LexiconError(f"{source} : une liste d'entrées est attendue")
    out: list[Entry] = []
    for i, raw in enumerate(data, 1):
        where = f"{source}, entrée {i}"
        if not isinstance(raw, dict):
            raise LexiconError(f"{where} : un dictionnaire est attendu")
        mot, regex, dire = raw.get("mot"), raw.get("regex"), raw.get("dire")
        if (mot is None) == (regex is None):
            raise LexiconError(f"{where} : exactement un de « mot » ou « regex »")
        if not isinstance(dire, str):
            raise LexiconError(f"{where} : « dire » (texte) est obligatoire")
        casse = raw.get("casse", True)
        if not isinstance(casse, bool):
            raise LexiconError(f"{where} : « casse » doit être true ou false")
        if mot is not None and (not isinstance(mot, str) or not mot):
            raise LexiconError(f"{where} : « mot » doit être un texte non vide")
        if regex is not None:
            try:
                re.compile(str(regex))
            except re.error as exc:
                raise LexiconError(f"{where} : expression régulière invalide ({exc})") from exc
        out.append(
            Entry(
                dire=dire,
                mot=mot,
                regex=None if regex is None else str(regex),
                casse=casse,
                ipa=raw.get("ipa"),
                commentaire=str(raw.get("commentaire") or ""),
            )
        )
    return out


def load_file(path: Path) -> list[Entry]:
    try:
        data = yaml.safe_load(path.read_text("utf-8"))
    except yaml.YAMLError as exc:
        raise LexiconError(f"{path} : YAML invalide ({exc})") from exc
    return parse_entries(data, str(path))


class Lexicon:
    def __init__(self, entries: list[Entry]) -> None:
        # entries : ordre de priorité décroissante (série avant global), sans doublon de clé
        self.entries = entries
        self._compiled = [(e, e.compile()) for e in entries]

    @classmethod
    def from_layers(cls, *layers: list[Entry]) -> Lexicon:
        """Couches de la plus générale (global) à la plus spécifique (série)."""
        merged: dict[tuple[str, bool], Entry] = {}
        order: list[tuple[str, bool]] = []
        for layer in layers:
            for e in layer:
                if e.key in merged:
                    order.remove(e.key)
                merged[e.key] = e
                order.append(e.key)
        return cls([merged[k] for k in reversed(order)])

    def apply(self, text: str) -> str:
        if not self._compiled or not text:
            return text
        out: list[str] = []
        pos = 0
        nxt: dict[int, re.Match[str] | None] = {}
        while pos <= len(text):
            best: tuple[int, int, int] | None = None  # (début, -longueur, rang)
            best_match: re.Match[str] | None = None
            best_entry: Entry | None = None
            for rank, (entry, pat) in enumerate(self._compiled):
                m = nxt.get(rank, False)
                if m is False or (m is not None and m.start() < pos):
                    m = _next_nonempty(pat, text, pos)
                    nxt[rank] = m
                if m is None:
                    continue
                cand = (m.start(), -(m.end() - m.start()), rank)
                if best is None or cand < best:
                    best, best_match, best_entry = cand, m, entry
            if best_match is None or best_entry is None:
                break
            out.append(text[pos : best_match.start()])
            out.append(_replacement(best_entry, best_match))
            pos = best_match.end()
        out.append(text[pos:])
        return "".join(out)


def _next_nonempty(pat: re.Pattern[str], text: str, pos: int) -> re.Match[str] | None:
    while pos <= len(text):
        m = pat.search(text, pos)
        if m is None:
            return None
        if m.end() > m.start():
            return m
        pos = m.start() + 1
    return None


def _replacement(entry: Entry, m: re.Match[str]) -> str:
    # Le texte « ipa » n'est exploité que par un moteur qui l'accepte ; aujourd'hui aucun
    # moteur branché ne le fait (voir engines/google.py) : on retombe toujours sur « dire ».
    if entry.mot is not None:
        return entry.dire
    return m.expand(entry.dire)


def default_lexicon_dir() -> Path:
    import os

    env = os.environ.get(LEXIQUES_ENV)
    if env:
        return Path(env)
    return Path(__file__).resolve().parents[2] / "lexiques"


def slug(name: str) -> str:
    import unicodedata

    plain = "".join(c for c in unicodedata.normalize("NFKD", name) if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]+", "-", plain.lower()).strip("-")


def load_lexicon(serie: str | None = None, directory: Path | None = None) -> Lexicon:
    """`global.yaml` puis `<serie>.yaml` (absents = vides ; série inconnue = erreur claire)."""
    directory = directory or default_lexicon_dir()
    layers: list[list[Entry]] = []
    g = directory / "global.yaml"
    layers.append(load_file(g) if g.exists() else [])
    if serie:
        s = directory / f"{slug(serie)}.yaml"
        if not s.exists():
            raise LexiconError(f"lexique de série introuvable : {s}")
        layers.append(load_file(s))
    return Lexicon.from_layers(*layers)
