"""Plan de synthèse, cache par segment, manifestes par chapitre."""

from __future__ import annotations

import hashlib
import json
import os
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .audio import make_silence, to_flac
from .engines import Engine
from .ledger import BudgetRefused, Ledger
from .lexicon import Lexicon
from .project import ChapterInfo, Project, Silences
from .segments import Item, Speech, chapter_items

KEY_VERSION = 1


def segment_key(text: str, engine: str, voice: str, params: dict[str, Any]) -> str:
    payload = json.dumps(
        {"v": KEY_VERSION, "texte": text, "moteur": engine, "voix": voice, "params": params},
        ensure_ascii=False,
        sort_keys=True,
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


@dataclass
class ChapterPlan:
    chapter: ChapterInfo
    items: list[Item]
    keys: list[str | None]  # clé de chaque élément Speech, None pour une pause

    def entries(self) -> list[dict[str, Any]]:
        """Forme sérialisée (manifeste) : {"k","t"} pour un texte, {"s"} pour un silence."""
        out: list[dict[str, Any]] = []
        for it, k in zip(self.items, self.keys, strict=True):
            out.append({"k": k, "t": it.text} if isinstance(it, Speech) else {"s": it.seconds})
        return out


@dataclass
class Plan:
    chapters: list[ChapterPlan]
    missing: dict[str, str] = field(default_factory=dict)  # clé -> texte, absents du cache

    @property
    def missing_chars(self) -> int:
        return sum(len(t) for t in self.missing.values())

    @property
    def total_chars(self) -> int:
        seen: dict[str, int] = {}
        for cp in self.chapters:
            for it, k in zip(cp.items, cp.keys, strict=True):
                if isinstance(it, Speech) and k:
                    seen[k] = len(it.text)
        return sum(seen.values())


def cache_path(project: Project, key: str) -> Path:
    return project.cache_dir / f"{key}.flac"


def make_plan(
    project: Project,
    engine: Engine,
    voice: str,
    params: dict[str, Any],
    lexicon: Lexicon,
    silences: Silences,
) -> Plan:
    plans: list[ChapterPlan] = []
    missing: dict[str, str] = {}
    for ch in project.chapters:
        title = lexicon.apply(ch.titre_lu)
        lines = [lexicon.apply(line) for line in ch.lines()]
        items = chapter_items(title, lines, engine.max_chars, silences)
        keys: list[str | None] = []
        for it in items:
            if isinstance(it, Speech):
                k = segment_key(it.text, engine.name, voice, params)
                keys.append(k)
                if not cache_path(project, k).exists():
                    missing.setdefault(k, it.text)
            else:
                keys.append(None)
        plans.append(ChapterPlan(ch, items, keys))
    return Plan(plans, missing)


# -- manifestes (dernier assemblage réussi de chaque chapitre) --------------------------------
def manifest_path(project: Project, ch: ChapterInfo) -> Path:
    return project.cache_dir / f"manifest-{ch.index:03d}.json"


def load_manifest(project: Project, ch: ChapterInfo) -> dict[str, Any] | None:
    p = manifest_path(project, ch)
    if not p.exists():
        return None
    try:
        return json.loads(p.read_text("utf-8"))
    except ValueError:
        return None  # manifeste illisible = chapitre à réassembler (sans conséquence de coût)


def save_manifest(project: Project, ch: ChapterInfo, data: dict[str, Any]) -> None:
    p = manifest_path(project, ch)
    tmp = p.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", "utf-8")
    os.replace(tmp, p)


# -- silences en cache ------------------------------------------------------------------------
def silence_file(project: Project, seconds: float) -> Path:
    p = project.cache_dir / f"silence-{round(seconds * 1000):06d}ms.flac"
    if not p.exists():
        part = p.with_suffix(".part")
        make_silence(part, seconds)
        os.replace(part, p)
    return p


# -- synthèse ---------------------------------------------------------------------------------
@dataclass
class SynthResult:
    segments: int = 0
    chars: int = 0
    stopped: str | None = None  # raison d'un arrêt net (plafond, palier, limite du run)
    error: str | None = None


def synthesize_missing(
    project: Project,
    engine: Engine,
    voice: str,
    params: dict[str, Any],
    plan: Plan,
    *,
    ledger: Ledger | None,
    max_total: int | None,
    run_cap: int | None,
    log: Callable[[str], None] = lambda _m: None,
) -> SynthResult:
    """Synthétise les segments absents du cache, dans l'ordre. Arrêt net si une limite tombe.

    `ledger` et `max_total` ne servent que pour un moteur payant : la réservation est écrite
    **avant** l'appel, confirmée après. Un échec d'appel arrête le run (aucun nouvel essai).
    """
    from .engines import EngineUnavailable

    res = SynthResult()
    project.cache_dir.mkdir(parents=True, exist_ok=True)
    total = len(plan.missing)
    for i, (key, text) in enumerate(plan.missing.items(), 1):
        n = len(text)
        if run_cap is not None and res.chars + n > run_cap:
            res.stopped = f"limite du run atteinte ({run_cap} caractères)"
            return res
        month = None
        if engine.is_paid:
            assert ledger is not None and max_total is not None
            try:
                month = ledger.reserve(engine.name, n, max_total)
            except BudgetRefused as exc:
                res.stopped = str(exc)
                return res
        tmp_wav = project.cache_dir / f"tmp-{key[:16]}.wav"
        wav: Path | None = None
        try:
            try:
                wav = engine.synthesize(text, voice, params, tmp_wav)
            except EngineUnavailable as exc:
                res.error = f"{engine.name} : arrêt après une erreur — {exc}"
                return res
            if month is not None and ledger is not None:
                ledger.confirm(engine.name, n, month)
            part = project.cache_dir / f"{key}.part"
            to_flac(wav, part)
            os.replace(part, cache_path(project, key))
        finally:
            tmp_wav.unlink(missing_ok=True)
            if wav is not None and wav != tmp_wav:
                wav.unlink(missing_ok=True)
        res.segments += 1
        res.chars += n
        if i % 20 == 0 or i == total:
            log(f"  {i}/{total} segments synthétisés")
    return res
