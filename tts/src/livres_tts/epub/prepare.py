"""Orchestration de `tts prepare` : EPUB → projet de travail."""

from __future__ import annotations

import hashlib
import json
import re
import shutil
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path

from ..text.normalize import NormalizeOptions, normalize
from . import clean
from .chapters import CHARS_PER_SECOND, Chapter, build_chapters
from .clean import CleanOptions, DocResult
from .extract import RawBook, load_epub
from .report import render_report

SCHEMA_VERSION = 1
SAMPLES_PER_CHAPTER = 3
SAMPLE_CHARS = 220


@dataclass
class PrepareOptions:
    keep_notes: bool = False
    chapter_level: int | None = None
    remove_dedications: bool = False
    normalize: NormalizeOptions = field(default_factory=NormalizeOptions)


@dataclass
class PrepareResult:
    project_dir: Path
    chapters: list[dict]
    total_chars: int
    total_seconds: float
    warnings: list[str]
    removals_by_kind: dict[str, int]


def safe_filename(title: str, limit: int = 80) -> str:
    name = re.sub(r'[\\/:*?"<>|\x00-\x1f]', " ", title)
    name = re.sub(r"\s+", " ", name).strip(" .")
    return name[:limit].strip(" .") or "Sans titre"


def chapter_lines(chapter: Chapter, norm: NormalizeOptions) -> tuple[list[str], list[str]]:
    """(lignes brutes, lignes normalisées) d'un chapitre ; une ligne = un paragraphe."""
    raw: list[str] = []
    final: list[str] = []
    for block in chapter.blocks:
        text = normalize(block.text, norm)
        if not text:
            continue
        if block.kind == "h" and text[-1].isalnum():
            text += "."  # un titre se termine comme une phrase : la voix marque la fin
        raw.append(block.text)
        final.append(text)
    for i, note in enumerate(chapter.notes, 1):
        raw.append(f"Note {i} : {note}")
        final.append(normalize(f"Note {i} : {note}", norm))
    return raw, final


def estimate_cost(chars: int) -> dict[str, float | str]:
    """Coût estimé avec la voix retenue (Google Chirp 3 HD) et rappel du palier gratuit."""
    from ..engines import get_engine

    engine = get_engine("google")
    price = engine.price_per_million_chars
    free = 1_000_000
    return {
        "moteur": "Google Chirp 3 HD",
        "prix_par_million": price,
        "cout_sans_palier": round(chars / 1_000_000 * price, 2),
        "cout_avec_palier": round(max(0, chars - free) / 1_000_000 * price, 2),
        "palier_gratuit": free,
    }


def _slug(text: str) -> str:
    text = re.sub(r"[^\w-]+", "-", text.lower()).strip("-")
    return text or "livre"


def prepare(
    epub_path: Path,
    out_dir: Path | None = None,
    options: PrepareOptions | None = None,
    log: Callable[[str], None] = lambda _m: None,
) -> PrepareResult:
    options = options or PrepareOptions()
    epub_path = Path(epub_path)
    raw: RawBook = load_epub(epub_path)
    log(f"EPUB lu : {raw.title} ({len(raw.spine)} documents, {len(raw.toc)} entrées de TdM)")
    out_dir = Path(out_dir) if out_dir else Path("projets") / _slug(epub_path.stem)

    copts = CleanOptions(options.keep_notes, options.remove_dedications)
    toc_paths = {e.path for e in raw.toc}
    spine = [d for d in raw.spine if d.linear or d.path in toc_paths]
    notes = clean.collect_notes([(d.path, d.html) for d in spine], copts)
    results: list[DocResult] = [clean.clean_document(d.path, d.html, notes, copts) for d in spine]
    dropped = clean.drop_running_heads(results)
    if dropped:
        log(f"{dropped} en-tête(s)/pied(s) de page répété(s) retiré(s)")

    chaptering = build_chapters(
        results, raw.toc, raw.title, options.chapter_level, options.keep_notes
    )
    warnings = list(raw.warnings) + chaptering.warnings
    if not chaptering.chapters:
        warnings.append("aucun chapitre produit")

    # Retraits : attribution à un chapitre
    index_of = {d.path: i for i, d in enumerate(results)}
    removals = [r for res in results for r in res.removals] + chaptering.removals
    for r in removals:
        r.chapter = chaptering.chapter_title_at(index_of.get(r.source, 0), r.position)

    # Écriture du projet
    if out_dir.exists():
        shutil.rmtree(out_dir / "chapitres", ignore_errors=True)
        shutil.rmtree(out_dir / "chapitres-bruts", ignore_errors=True)
    (out_dir / "chapitres").mkdir(parents=True, exist_ok=True)
    (out_dir / "chapitres-bruts").mkdir(parents=True, exist_ok=True)

    width = max(2, len(str(len(chaptering.chapters))))
    entries: list[dict] = []
    samples: dict[int, list[tuple[str, str]]] = {}
    total_chars = 0
    for ch in chaptering.chapters:
        raw_lines, final_lines = chapter_lines(ch, options.normalize)
        name = f"{ch.index:0{width}d} - {safe_filename(ch.title)}.txt"
        (out_dir / "chapitres" / name).write_text("\n".join(final_lines) + "\n", "utf-8")
        (out_dir / "chapitres-bruts" / name).write_text("\n".join(raw_lines) + "\n", "utf-8")
        chars = sum(len(line) for line in final_lines)
        total_chars += chars
        entries.append(
            {
                "index": ch.index,
                "titre": ch.title,
                "titre_lu": normalize(ch.title, options.normalize),
                "fichier": f"chapitres/{name}",
                "fichier_brut": f"chapitres-bruts/{name}",
                "caracteres": chars,
                "duree_estimee_s": round(chars / CHARS_PER_SECOND),
            }
        )
        changed = [(a, b) for a, b in zip(raw_lines, final_lines, strict=True) if a != b]
        samples[ch.index] = [
            (a[:SAMPLE_CHARS], b[:SAMPLE_CHARS]) for a, b in changed[:SAMPLES_PER_CHAPTER]
        ]

    cover_name = None
    if raw.cover:
        cover_name = f"couverture.{raw.cover[1]}"
        (out_dir / cover_name).write_bytes(raw.cover[0])

    total_seconds = total_chars / CHARS_PER_SECOND
    opts_dict = {
        "garder_notes": options.keep_notes,
        "niveau_chapitres": options.chapter_level if options.chapter_level else "auto",
        "retirer_dedicaces": options.remove_dedications,
    }
    project = {
        "schemaVersion": SCHEMA_VERSION,
        "cree_le": datetime.now(UTC).isoformat(timespec="seconds"),
        "source": {
            "fichier": epub_path.name,
            "sha256": hashlib.sha256(epub_path.read_bytes()).hexdigest(),
        },
        "livre": {
            "titre": raw.title,
            "auteurs": raw.authors,
            "langue": raw.language,
            "annee": raw.year,
            "serie": raw.series,
            "tome": raw.series_index,
        },
        "options": opts_dict,
        "chapitrage": {"source": chaptering.source, "niveau": chaptering.level},
        "couverture": cover_name,
        "chapitres": entries,
        "statistiques": {
            "caracteres": total_chars,
            "duree_estimee_s": round(total_seconds),
            "passages_retires": len(removals),
        },
        "avertissements": warnings,
    }
    (out_dir / "projet.json").write_text(
        json.dumps(project, ensure_ascii=False, indent=2) + "\n", "utf-8"
    )
    (out_dir / "rapport.html").write_text(
        render_report(
            title=raw.title,
            authors=raw.authors,
            source_name=epub_path.name,
            chapters=entries,
            removals=removals,
            samples=samples,
            warnings=warnings,
            total_chars=total_chars,
            total_seconds=total_seconds,
            level=chaptering.level,
            chapter_source={"toc": "table des matières", "titres": "titres du texte",
                            "documents": "un chapitre par document"}[chaptering.source],
            options=opts_dict,
        ),
        "utf-8",
    )
    by_kind: dict[str, int] = {}
    for r in removals:
        by_kind[r.kind] = by_kind.get(r.kind, 0) + 1
    return PrepareResult(out_dir, entries, total_chars, total_seconds, warnings, by_kind)
