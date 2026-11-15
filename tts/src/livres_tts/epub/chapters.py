"""Chapitrage : de la table des matières (ou des titres) à une liste de chapitres.

Règle de choix du niveau (documentée aussi dans le README) :

1. Chaque entrée de la table des matières (TdM) a un niveau (1 = premier niveau). Un niveau
   `L` donne un chapitre par entrée de niveau ≤ `L` : les entrées plus profondes sont fondues
   dans le chapitre qui les contient. Une « Partie I » qui contient des chapitres n'est donc un
   chapitre à part entière que si `L` est 1.
2. Sans `--niveau-chapitres`, on essaie les niveaux du plus fin au plus grossier et on retient
   le **plus fin** dont au moins 80 % du texte tient dans des chapitres de 3 à 60 minutes
   estimées (≈ 15 caractères par seconde) ; à défaut, le niveau qui maximise cette part.
3. Un chapitre de moins de 300 caractères (page « Première partie », intercalaire) est fondu
   dans le suivant (le dernier, dans le précédent) : son texte reste lu, sans fichier à part.
4. Le texte situé avant la première entrée de la TdM est retiré s'il fait moins de
   800 caractères (page liminaire), sinon il devient un chapitre « Avant-propos ».
5. TdM absente ou inexploitable (moins de 2 entrées retrouvées) : repli sur les titres h1…h6
   du texte, avec la même règle de niveau ; sans titres, un chapitre par document.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from .clean import MIN_LEADING_CHARS, R_LEADING, Block, DocResult, Removal, excerpt
from .extract import TocEntry

CHARS_PER_SECOND = 15
MIN_CHAPTER_CHARS = 3 * 60 * CHARS_PER_SECOND  # 3 minutes
MAX_CHAPTER_CHARS = 60 * 60 * CHARS_PER_SECOND  # 60 minutes
TINY_CHARS = 300
GOOD_SHARE = 0.8


@dataclass
class Chapter:
    index: int
    title: str
    blocks: list[Block] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)  # notes appelées (si --garder-notes)
    start: tuple[int, int] = (0, 0)  # (document, bloc) du premier bloc

    @property
    def chars(self) -> int:
        return sum(len(b.text) for b in self.blocks)


@dataclass
class Boundary:
    g: int  # indice global du bloc de départ
    title: str
    level: int
    doc: int
    block: int


@dataclass
class ChaptersResult:
    chapters: list[Chapter]
    level: int  # niveau retenu (0 : un chapitre par document)
    source: str  # "toc", "titres" ou "documents"
    removals: list[Removal] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    def chapter_title_at(self, doc: int, block: int) -> str:
        """Titre du chapitre qui contient la position (document, bloc)."""
        title = "(avant le premier chapitre)"
        for ch in self.chapters:
            if ch.start <= (doc, block):
                title = f"{ch.index:02d} - {ch.title}"
        return title


def _estimate_minutes(chars: int) -> float:
    return chars / CHARS_PER_SECOND / 60


def _segment_sizes(starts: list[int], sizes_prefix: list[int]) -> list[int]:
    total = len(sizes_prefix) - 1
    bounds = starts + [total]
    return [sizes_prefix[bounds[i + 1]] - sizes_prefix[bounds[i]] for i in range(len(starts))]


def merge_tiny(sizes: list[int]) -> list[int]:
    """Tailles après fusion des chapitres minuscules (avec le suivant, le dernier avec le
    précédent). Fonction pure, utilisée pour évaluer un niveau."""
    out: list[int] = []
    carry = 0
    for size in sizes:
        size += carry
        carry = 0
        if size < TINY_CHARS:
            carry = size
        else:
            out.append(size)
    if carry:
        if out:
            out[-1] += carry
        else:
            out.append(carry)
    return out


def good_share(sizes: list[int]) -> float:
    total = sum(sizes)
    if not total:
        return 0.0
    good = sum(s for s in sizes if MIN_CHAPTER_CHARS <= s <= MAX_CHAPTER_CHARS)
    return good / total


def choose_level(levels: list[int], sizes_for: dict[int, list[int]]) -> int:
    """Le niveau le plus fin dont ≥ 80 % du texte est en chapitres de 3 à 60 min."""
    best, best_share = levels[0], -1.0
    for level in sorted(levels, reverse=True):
        share = good_share(merge_tiny(sizes_for[level]))
        if share >= GOOD_SHARE:
            return level
        if share > best_share:
            best, best_share = level, share
    return best


def _toc_boundaries(
    docs: list[DocResult], toc: list[TocEntry], starts: list[int], warnings: list[str]
) -> list[Boundary]:
    by_path = {d.path: i for i, d in enumerate(docs)}
    by_name = {d.path.rsplit("/", 1)[-1]: i for i, d in enumerate(docs)}
    out: list[Boundary] = []
    for entry in toc:
        di = by_path.get(entry.path)
        if di is None:
            di = by_name.get(entry.path.rsplit("/", 1)[-1])
        if di is None:
            warnings.append(f"entrée de la table des matières hors lecture : « {entry.title} »")
            continue
        doc = docs[di]
        if doc.removed_as:
            continue  # page de titre, sommaire… : retirée, son entrée n'a plus d'objet
        bi = 0
        if entry.fragment:
            for i, b in enumerate(doc.blocks):
                if entry.fragment in b.anchors:
                    bi = i
                    break
            else:
                warnings.append(
                    f"ancre #{entry.fragment} introuvable dans {doc.path} : début du document"
                )
        if not doc.blocks:
            continue
        out.append(Boundary(starts[di] + bi, entry.title, entry.level, di, bi))
    return out


def _heading_boundaries(docs: list[DocResult], starts: list[int]) -> list[Boundary]:
    raw: list[tuple[int, Boundary]] = []
    for di, doc in enumerate(docs):
        for bi, b in enumerate(doc.blocks):
            if b.kind == "h":
                raw.append((b.level, Boundary(starts[di] + bi, b.text, 0, di, bi)))
    ranks = {lvl: i + 1 for i, lvl in enumerate(sorted({lvl for lvl, _ in raw}))}
    for lvl, bd in raw:
        bd.level = ranks[lvl]
    return [bd for _, bd in raw]


def _document_boundaries(docs: list[DocResult], starts: list[int]) -> list[Boundary]:
    out = []
    for di, doc in enumerate(docs):
        if doc.blocks:
            first = doc.blocks[0]
            title = first.text if first.kind == "h" else f"Partie {len(out) + 1}"
            out.append(Boundary(starts[di], excerpt(title, 60), 1, di, 0))
    return out


def _dedupe(boundaries: list[Boundary]) -> list[Boundary]:
    """Plusieurs entrées au même endroit (« Partie I » et « Chapitre 1 ») : la plus profonde."""
    out: list[Boundary] = []
    for bd in sorted(boundaries, key=lambda b: b.g):  # tri stable : ordre de la TdM conservé
        if out and out[-1].g == bd.g:
            out[-1] = bd
        else:
            out.append(bd)
    return out


def build_chapters(
    docs: list[DocResult],
    toc: list[TocEntry],
    book_title: str,
    level: int | None = None,
    keep_notes: bool = False,
) -> ChaptersResult:
    warnings: list[str] = []
    flat: list[tuple[int, int, Block]] = [
        (di, bi, b) for di, d in enumerate(docs) for bi, b in enumerate(d.blocks)
    ]
    starts: list[int] = []
    n = 0
    for d in docs:
        starts.append(n)
        n += len(d.blocks)
    prefix = [0]
    for _, _, b in flat:
        prefix.append(prefix[-1] + len(b.text))
    result = ChaptersResult([], 0, "toc")
    if not flat:
        warnings.append("aucun texte exploitable dans le livre")
        result.warnings = warnings
        return result

    boundaries = _dedupe(_toc_boundaries(docs, toc, starts, warnings))
    source = "toc"
    if len({b.g for b in boundaries}) < 2:
        if toc:
            warnings.append("table des matières inexploitable : repli sur les titres du texte")
        boundaries, source = _dedupe(_heading_boundaries(docs, starts)), "titres"
        if len(boundaries) < 2:
            boundaries, source = _dedupe(_document_boundaries(docs, starts)), "documents"
            if len(boundaries) >= 2:
                warnings.append("ni table des matières ni titres : un chapitre par document")
    result.source = source
    if not boundaries:
        boundaries = [Boundary(0, book_title, 1, 0, 0)]

    levels = sorted({b.level for b in boundaries})
    sizes_for: dict[int, list[int]] = {}
    for lvl in levels:
        chosen = _dedupe([b for b in boundaries if b.level <= lvl])
        sizes_for[lvl] = _segment_sizes([b.g for b in chosen], prefix)
    if level is not None:
        eff = max(1, level)
        usable = [lv for lv in levels if lv <= eff]
        if not usable:
            usable = [levels[0]]
        use_level = max(usable)
        if level not in levels:
            warnings.append(
                f"niveau {level} absent de la table des matières (niveaux : "
                f"{', '.join(map(str, levels))}) : niveau {use_level} utilisé"
            )
    else:
        use_level = choose_level(levels, sizes_for)
    result.level = use_level
    chosen = _dedupe([b for b in boundaries if b.level <= use_level])

    # Segments : texte avant le premier repère, puis un segment par repère.
    segs: list[tuple[Boundary | None, int, int]] = []  # (repère, g début, g fin exclu)
    edges = [b.g for b in chosen] + [len(flat)]
    if chosen[0].g > 0:
        segs.append((None, 0, chosen[0].g))
    for i, bd in enumerate(chosen):
        segs.append((bd, bd.g, edges[i + 1]))

    chapters: list[Chapter] = []
    pending: list[Block] = []
    pending_start: tuple[int, int] = (0, 0)
    for bd, g0, g1 in segs:
        blocks = [flat[g][2] for g in range(g0, g1)]
        chars = sum(len(b.text) for b in blocks)
        if bd is None:
            if chars < MIN_LEADING_CHARS:
                result.removals.append(
                    Removal(
                        R_LEADING,
                        docs[flat[g0][0]].path,
                        excerpt(blocks[0].text),
                        position=flat[g0][1],
                    )  # fmt: skip
                )
                continue
            title = next((b.text for b in blocks if b.kind == "h"), "Avant-propos")
            chapters.append(Chapter(0, excerpt(title, 100), blocks, start=flat[g0][:2]))
            continue
        if not pending:
            pending_start = flat[g0][:2] if g0 < len(flat) else (0, 0)
        pending.extend(blocks)
        if sum(len(b.text) for b in pending) < TINY_CHARS:
            continue  # trop court : fondu dans le chapitre suivant
        title = re.sub(r"\s+", " ", bd.title).strip() or f"Chapitre {len(chapters) + 1}"
        chapters.append(Chapter(0, title, pending, start=pending_start))
        pending = []
    if pending:  # dernier chapitre minuscule : fondu dans le précédent
        if chapters:
            chapters[-1].blocks.extend(pending)
        else:
            title = chosen[-1].title or book_title
            chapters.append(Chapter(0, title, pending, start=pending_start))
    for i, ch in enumerate(chapters, 1):
        ch.index = i
        if keep_notes:
            ch.notes = [note for b in ch.blocks for note in b.notes]
    result.chapters = chapters
    long_ones = [c for c in chapters if c.chars > MAX_CHAPTER_CHARS]
    for c in long_ones:
        warnings.append(
            f"chapitre {c.index} « {excerpt(c.title, 40)} » long : "
            f"≈ {_estimate_minutes(c.chars):.0f} min"
        )
    result.warnings = warnings
    return result
