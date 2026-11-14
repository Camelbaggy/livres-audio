"""Banc d'essai : même extrait avec chaque moteur/voix, anonymisé, page d'écoute à l'aveugle."""

from __future__ import annotations

import json
import random
import string
import tempfile
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

from .audio import concat_and_encode
from .engines import Engine, EngineUnavailable, get_engine
from .page import render_page
from .textsplit import chunk_text

DEFAULT_BUDGET_MAX = 0.50  # euros, pour l'ensemble du banc


class PaidEngineRefused(Exception):
    pass


def estimate_cost(engine: Engine, text: str) -> tuple[int, float]:
    """(caractères réellement envoyés, coût maximal en euros hors palier gratuit)."""
    chars = sum(len(c) for c in chunk_text(text, engine.max_chars))
    return chars, chars * engine.price_per_million_chars / 1_000_000


class BudgetExceeded(PaidEngineRefused):
    pass


@dataclass
class BenchEntry:
    engine: str
    voice: str
    file: Path
    seconds: float = 0.0


@dataclass
class BenchResult:
    out_dir: Path
    entries: list[BenchEntry] = field(default_factory=list)
    skipped: dict[str, str] = field(default_factory=dict)


def parse_voices(spec: str | None) -> dict[str, str]:
    """`moteur=voix,moteur2=voix2` -> dict."""
    out: dict[str, str] = {}
    if not spec:
        return out
    for item in spec.split(","):
        if not item.strip():
            continue
        if "=" not in item:
            raise ValueError(f"--voix attend moteur=voix, reçu : {item!r}")
        eng, voice = item.split("=", 1)
        out[eng.strip()] = voice.strip()
    return out


def anonymous_labels(n: int) -> list[str]:
    letters = string.ascii_uppercase
    return [letters[i] if i < 26 else letters[i // 26 - 1] + letters[i % 26] for i in range(n)]


def synthesize_text(engine: Engine, text: str, voice: str, workdir: Path) -> Path:
    parts: list[Path] = []
    for i, chunk in enumerate(chunk_text(text, engine.max_chars)):
        parts.append(engine.synthesize(chunk, voice, None, workdir / f"{engine.name}-{i:03d}.wav"))
    final = workdir / f"{engine.name}.m4a"
    return concat_and_encode(parts, final)


def run_bench(
    text: str,
    engine_names: list[str],
    out_dir: Path,
    voices: dict[str, str] | None = None,
    allow_paid: bool = False,
    budget_max: float = DEFAULT_BUDGET_MAX,
    seed: int | None = None,
    engine_factory: Callable[[str], Engine] = get_engine,
    log: Callable[[str], None] = lambda _m: None,
) -> BenchResult:
    voices = voices or {}
    engines: list[tuple[Engine, str | None]] = []
    for spec in engine_names:
        # « moteur:voix » permet de comparer plusieurs voix d'un même moteur.
        name, _, explicit_voice = spec.partition(":")
        eng = engine_factory(name)
        if eng.is_paid and not allow_paid:
            raise PaidEngineRefused(
                f"Le moteur {name} est payant : relancer avec --autoriser-payant "
                "(après accord de l'utilisateur)."
            )
        engines.append((eng, explicit_voice or None))

    # Garde-fou de coût : estimation affichée même si le palier gratuit devrait tout couvrir.
    total = 0.0
    for eng, _voice in engines:
        if eng.is_paid:
            chars, cost = estimate_cost(eng, text)
            total += cost
            log(
                f"{eng.name} : {chars} caractères, coût maximal estimé {cost:.3f} €"
                " (palier gratuit non déduit)"
            )
    if total > budget_max:
        raise BudgetExceeded(
            f"Coût maximal estimé du banc : {total:.2f} € > --budget-max {budget_max:.2f} €. "
            "Retirer un moteur ou relever le plafond après accord de l'utilisateur."
        )

    out_dir.mkdir(parents=True, exist_ok=True)
    result = BenchResult(out_dir=out_dir)
    produced: list[tuple[Engine, str, Path]] = []
    with tempfile.TemporaryDirectory() as tmp:
        for eng, explicit_voice in engines:
            voice = explicit_voice or voices.get(eng.name) or eng.default_voice
            try:
                eng.check_available()
                log(f"{eng.name} ({voice}) : synthèse…")
                workdir = Path(tmp) / f"{eng.name}-{len(produced)}"
                workdir.mkdir()
                produced.append((eng, voice, synthesize_text(eng, text, voice, workdir)))
            except EngineUnavailable as exc:
                result.skipped[f"{eng.name}:{voice}"] = str(exc)
                log(f"{eng.name} ({voice}) : ignoré — {exc}")
        order = list(range(len(produced)))
        random.Random(seed).shuffle(order)
        labels = anonymous_labels(len(produced))
        for label, idx in zip(labels, order, strict=True):
            eng, voice, src = produced[idx]
            dst = out_dir / f"voix-{label}.m4a"
            dst.write_bytes(src.read_bytes())
            result.entries.append(BenchEntry(eng.name, voice, dst))

    result.entries.sort(key=lambda e: e.file.name)
    bench_id = datetime.now().strftime("%Y%m%d-%H%M%S")
    key = {
        "id": bench_id,
        "avertissement": "Ne pas ouvrir avant d'avoir noté toutes les voix.",
        "voix": {
            e.file.stem.removeprefix("voix-"): {"moteur": e.engine, "voix": e.voice}
            for e in result.entries
        },
        "ignores": result.skipped,
    }
    (out_dir / "cle.json").write_text(json.dumps(key, ensure_ascii=False, indent=2), "utf-8")
    page = render_page(bench_id, [e.file.name for e in result.entries], text)
    (out_dir / "ecoute.html").write_text(page, "utf-8")
    return result
