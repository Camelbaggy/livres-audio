"""Orchestration de `tts build` / `tts fix` / `tts estimate` / `tts say`, avec garde-fou de coût.

Règles de coût (moteur payant uniquement) :
- usage du mois = registre (`ledger`), palier gratuit = `engine.free_chars_per_month` ;
- sans `--budget-max`, on ne dépasse jamais le palier gratuit du mois : si ce qui reste à
  synthétiser le dépasse, refus avant tout appel (sauf `--jusqu-au-palier`, arrêt net au palier) ;
- avec `--budget-max` (€, au-delà du palier, pour ce run), arrêt net au plafond ;
- la limite est aussi imposée à chaque appel par le registre (réservation avant l'appel).
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .audio import concat_files, encode_m4a, measure_loudness
from .engines import Engine, EngineUnavailable
from .epub.chapters import CHARS_PER_SECOND
from .ledger import Ledger, LedgerError
from .lexicon import Lexicon, load_lexicon
from .project import BuildSettings, ChapterInfo, Project
from .segments import Pause
from .synth import (
    ChapterPlan,
    Plan,
    SynthResult,
    cache_path,
    load_manifest,
    make_plan,
    save_manifest,
    silence_file,
    synthesize_missing,
)


class BuildRefused(Exception):
    """Refus avant tout appel (coût, registre, moteur indisponible…)."""


@dataclass
class RunOptions:
    budget_max: float | None = None  # € au-delà du palier gratuit, pour ce run
    max_caracteres: int | None = None
    jusqu_au_palier: bool = False
    oui: bool = False


@dataclass
class Limits:
    """Limites d'un run, calculées avant le premier appel."""

    paid: bool
    used: int = 0
    free_left: int = 0
    run_chars: int = 0  # caractères qui seront (au plus) envoyés
    over_chars: int = 0  # part au-delà du palier gratuit
    cost: float = 0.0  # coût au-delà du palier, en €
    max_total: int | None = None  # plafond mensuel imposé au registre
    run_cap: int | None = None


def voice_label(voice: str) -> str:
    last = voice.split("-")[-1] if "-" in voice else voice
    return re.sub(r"(Multilingual)?Neural$|^Multilingual", "", last) or voice


def fmt_int(n: int) -> str:
    return f"{n:,}".replace(",", " ")


def fmt_duration(seconds: float) -> str:
    s = int(round(seconds))
    return f"{s // 3600} h {s % 3600 // 60:02d} min"


FREE_TIER_SAFETY = 0.95
"""Part du palier gratuit mensuel que `build` s'autorise à consommer sans `--budget-max`."""


def compute_limits(
    engine: Engine, missing_chars: int, opts: RunOptions, ledger: Ledger | None
) -> Limits:
    """Calcule les limites ; lève BuildRefused si le run dépasserait le palier sans accord."""
    run_chars = missing_chars
    if opts.max_caracteres is not None:
        run_chars = min(run_chars, opts.max_caracteres)
    if not engine.is_paid:
        return Limits(paid=False, run_chars=run_chars, run_cap=opts.max_caracteres)
    if ledger is None:
        raise BuildRefused("moteur payant sans registre des dépenses : refus")
    try:
        used = ledger.used(engine.name)
    except LedgerError as exc:
        raise BuildRefused(str(exc)) from exc
    # Marge de sécurité : usages hors registre (banc d'essai, console Google) et écarts de comptage.
    free = int(engine.free_chars_per_month * FREE_TIER_SAFETY)
    free_left = max(0, free - used)
    over = max(0, run_chars - free_left)
    price = engine.price_per_million_chars
    cost = over * price / 1_000_000
    lim = Limits(
        paid=True,
        used=used,
        free_left=free_left,
        run_chars=run_chars,
        over_chars=over,
        cost=cost,
        run_cap=opts.max_caracteres,
    )
    if opts.budget_max is not None:
        extra = int(opts.budget_max * 1_000_000 / price) if price > 0 else 0
        lim.max_total = max(used, free) + extra
        lim.run_chars = run_chars
        if cost > opts.budget_max:
            lim.over_chars = extra  # le run s'arrêtera net au plafond
            lim.cost = extra * price / 1_000_000
    else:
        lim.max_total = free
        if over > 0 and not opts.jusqu_au_palier:
            raise BuildRefused(
                f"{fmt_int(run_chars)} caractères à synthétiser, mais il ne reste que "
                f"{fmt_int(free_left)} caractères de palier gratuit ce mois-ci avec "
                f"{engine.name} (utilisé : {fmt_int(used)} / {fmt_int(free)}). Le dépassement "
                f"coûterait {cost:.2f} € ({fmt_int(over)} car. à {price:.2f} €/M). "
                "Options : --budget-max <€> pour accepter un dépassement plafonné, "
                "--jusqu-au-palier pour synthétiser jusqu'à la fin du palier puis s'arrêter "
                "(reprise le mois suivant), ou --max-caracteres N pour limiter le run."
            )
        if over > 0:
            lim.run_chars = free_left
            lim.over_chars = 0
            lim.cost = 0.0
    return lim


def describe_limits(engine: Engine, lim: Limits, missing_chars: int) -> list[str]:
    lines = [f"À synthétiser : {fmt_int(missing_chars)} caractères (hors cache)."]
    if not lim.paid:
        lines.append(f"Moteur gratuit ({engine.name}) : pas de coût.")
        return lines
    free = engine.free_chars_per_month
    lines.append(
        f"Palier gratuit {engine.name} ce mois-ci : {fmt_int(lim.free_left)} restants "
        f"sur {fmt_int(free)} (déjà compté : {fmt_int(lim.used)})."
    )
    lines.append(
        f"Ce run enverra au plus {fmt_int(lim.run_chars)} caractères, dont "
        f"{fmt_int(lim.over_chars)} au-delà du palier : coût {lim.cost:.2f} € "
        f"({engine.price_per_million_chars:.2f} €/M car.)."
    )
    return lines


def default_confirm(prompt: str) -> bool:
    if not sys.stdin.isatty():
        return False
    try:
        return input(prompt + " [o/N] ").strip().lower() in {"o", "oui", "y", "yes"}
    except EOFError:
        return False


# -- assemblage -------------------------------------------------------------------------------
def build_tags(project: Project, ch: ChapterInfo, voice: str, total: int) -> dict[str, str]:
    livre = project.data.get("livre", {})
    auteurs = ", ".join(livre.get("auteurs") or [])
    narrateur = f"Voix de synthèse ({voice_label(voice)})"
    tags = {
        "title": ch.titre,
        "album": livre.get("titre") or "",
        "composer": narrateur,
        "genre": "Livre audio",
        "track": f"{ch.index}/{total}",
    }
    if auteurs:
        tags["artist"] = auteurs
        tags["album_artist"] = auteurs
    if livre.get("annee"):
        tags["date"] = str(livre["annee"])
    serie = livre.get("serie")
    if serie:
        tags["grouping"] = str(serie)
        tome = livre.get("tome")
        tags["comment"] = f"{serie}, tome {tome}" if tome else str(serie)
    return {k: v for k, v in tags.items() if v}


def chapter_signature(cp: ChapterPlan, tags: dict[str, str], cover: Path | None) -> str:
    payload = {
        "items": cp.entries(),
        "tags": tags,
        "cover": hashlib.sha256(cover.read_bytes()).hexdigest() if cover else None,
    }
    return hashlib.sha256(
        json.dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
    ).hexdigest()


def output_path(project: Project, cp: ChapterPlan) -> Path:
    return project.out_dir / f"{cp.chapter.stem}.m4a"


def chapters_to_assemble(
    project: Project, plan: Plan, voice: str
) -> list[tuple[ChapterPlan, dict[str, str], str]]:
    """Chapitres dont la sortie manque ou diffère du dernier assemblage."""
    total = len(plan.chapters)
    out = []
    for cp in plan.chapters:
        tags = build_tags(project, cp.chapter, voice, total)
        sig = chapter_signature(cp, tags, project.cover)
        old = load_manifest(project, cp.chapter)
        if not output_path(project, cp).exists() or not old or old.get("signature") != sig:
            out.append((cp, tags, sig))
    return out


def is_complete(project: Project, cp: ChapterPlan) -> bool:
    return all(k is None or cache_path(project, k).exists() for k in cp.keys)


def assemble_chapter(
    project: Project, cp: ChapterPlan, tags: dict[str, str], sig: str
) -> Path:
    parts: list[Path] = []
    for it, k in zip(cp.items, cp.keys, strict=True):
        if isinstance(it, Pause):
            parts.append(silence_file(project, it.seconds))
        else:
            assert k is not None
            parts.append(cache_path(project, k))
    project.out_dir.mkdir(parents=True, exist_ok=True)
    tmp_wav = project.cache_dir / f"assemblage-{cp.chapter.index:03d}.wav"
    list_file = project.cache_dir / f"assemblage-{cp.chapter.index:03d}.txt"
    final = output_path(project, cp)
    part = final.with_name(final.stem + ".part.m4a")
    try:
        concat_files(parts, list_file, tmp_wav)
        measured = measure_loudness(tmp_wav)
        encode_m4a(tmp_wav, part, tags, project.cover, measured)
        os.replace(part, final)
    finally:
        for p in (tmp_wav, list_file, part):
            p.unlink(missing_ok=True)
    save_manifest(project, cp.chapter, {"signature": sig, "items": cp.entries()})
    return final


# -- diff pour `fix` --------------------------------------------------------------------------
def describe_changes(
    project: Project, todo: list[tuple[ChapterPlan, Any, str]]
) -> list[str]:
    lines: list[str] = []
    shown = 0
    for cp, _tags, _sig in todo:
        old = load_manifest(project, cp.chapter)
        old_keys = {e["k"] for e in (old or {}).get("items", []) if e.get("k")}
        new_texts = [
            (it.text, k)
            for it, k in zip(cp.items, cp.keys, strict=True)
            if k and k not in old_keys
        ]
        if old is None:
            continue
        lines.append(f"  chapitre {cp.chapter.stem} : {len(new_texts)} segment(s) modifié(s)")
        for text, _k in new_texts[:3]:
            if shown < 12:
                lines.append(f"      → {text[:110]}{'…' if len(text) > 110 else ''}")
                shown += 1
    return lines


# -- exécution --------------------------------------------------------------------------------
@dataclass
class RunReport:
    plan: Plan | None = None
    limits: Limits | None = None
    synth: SynthResult = field(default_factory=SynthResult)
    assembled: list[Path] = field(default_factory=list)
    pending_chapters: list[str] = field(default_factory=list)
    aborted: bool = False
    nothing: bool = False

    @property
    def partial(self) -> bool:
        return bool(self.synth.stopped or self.synth.error or self.pending_chapters)


def run(
    project: Project,
    settings: BuildSettings,
    engine: Engine,
    opts: RunOptions,
    *,
    ledger: Ledger | None,
    log: Callable[[str], None] = print,
    confirm: Callable[[str], bool] = default_confirm,
    fix: bool = False,
) -> RunReport:
    lexicon = _lexicon(settings)
    plan = make_plan(project, engine, settings.voix, settings.params, lexicon, settings.silences)
    report = RunReport(plan=plan)
    todo = chapters_to_assemble(project, plan, settings.voix)
    log(
        f"Projet : {len(plan.chapters)} chapitre(s), {fmt_int(plan.total_chars)} caractères au "
        f"total, durée estimée {fmt_duration(plan.total_chars / CHARS_PER_SECOND)}."
    )
    if fix:
        lines = describe_changes(project, todo)
        log(f"Changements depuis le dernier assemblage : {len(todo)} chapitre(s) touché(s).")
        for ln in lines:
            log(ln)
    if not plan.missing and not todo:
        log("Rien à faire : tout est déjà synthétisé et assemblé.")
        report.nothing = True
        return report

    if plan.missing:
        lim = compute_limits(engine, plan.missing_chars, opts, ledger)
        report.limits = lim
        for ln in describe_limits(engine, lim, plan.missing_chars):
            log(ln)
        try:
            engine.check_available()
        except EngineUnavailable as exc:
            raise BuildRefused(str(exc)) from exc
        if lim.paid and not opts.oui:
            if not confirm("Lancer la synthèse payante (au palier/plafond indiqué) ?"):
                log("Annulé : aucun appel n'a été fait.")
                report.aborted = True
                return report
        project.save_settings(settings)
        report.synth = synthesize_missing(
            project,
            engine,
            settings.voix,
            settings.params,
            plan,
            ledger=ledger,
            max_total=lim.max_total,
            run_cap=lim.run_cap,
            log=log,
        )
        if report.synth.error:
            log(f"Erreur : {report.synth.error}")
        if report.synth.stopped:
            log(f"Arrêt net : {report.synth.stopped}. Reprise possible (le cache est conservé).")
    else:
        project.save_settings(settings)

    for cp, tags, sig in todo:
        if is_complete(project, cp):
            log(f"Assemblage : {cp.chapter.stem}")
            report.assembled.append(assemble_chapter(project, cp, tags, sig))
        else:
            report.pending_chapters.append(cp.chapter.stem)
    if report.pending_chapters:
        log(
            f"{len(report.pending_chapters)} chapitre(s) pas encore assemblable(s) "
            "(segments manquants)."
        )
    return report


def _lexicon(settings: BuildSettings) -> Lexicon:
    d = Path(settings.lexiques) if settings.lexiques else None
    return load_lexicon(settings.serie, d)


def estimate(
    project: Project, settings: BuildSettings, engine: Engine, ledger: Ledger | None
) -> list[str]:
    plan = make_plan(
        project, engine, settings.voix, settings.params, _lexicon(settings), settings.silences
    )
    total = plan.total_chars
    lines = [
        f"{len(plan.chapters)} chapitre(s), {fmt_int(total)} caractères à envoyer au total "
        f"(titres lus compris), durée estimée {fmt_duration(total / CHARS_PER_SECOND)}.",
        f"Déjà en cache : {fmt_int(total - plan.missing_chars)} ; "
        f"restant à synthétiser : {fmt_int(plan.missing_chars)} caractères.",
    ]
    if not engine.is_paid:
        lines.append(f"Moteur gratuit ({engine.name}) : coût 0 €.")
        return lines
    price = engine.price_per_million_chars
    lines.append(
        f"Tarif {engine.name} : {price:.2f} €/M caractères ; coût au tarif plein du restant : "
        f"{plan.missing_chars * price / 1e6:.2f} €."
    )
    if ledger is None:
        return lines
    try:
        used = ledger.used(engine.name)
    except LedgerError as exc:
        lines.append(f"Registre des dépenses inutilisable : {exc}")
        return lines
    free = engine.free_chars_per_month
    left = max(0, free - used)
    over = max(0, plan.missing_chars - left)
    lines.append(
        f"Palier gratuit du mois : {fmt_int(used)} / {fmt_int(free)} utilisés, "
        f"{fmt_int(left)} restants."
    )
    lines.append(
        f"Part du restant couverte par le palier : {fmt_int(min(plan.missing_chars, left))} ; "
        f"au-delà : {fmt_int(over)} caractères = {over * price / 1e6:.2f} €."
    )
    return lines


# -- say --------------------------------------------------------------------------------------
def say(
    phrase: str,
    engine: Engine,
    voice: str,
    out: Path,
    *,
    serie: str | None,
    lexiques: Path | None,
    opts: RunOptions,
    ledger: Ledger | None,
    params: dict[str, Any] | None = None,
    log: Callable[[str], None] = print,
) -> Path:
    """Phrase courte : normalisation + lexique, synthèse, M4A. Même garde-fou de coût."""
    import tempfile

    from .audio import concat_and_encode
    from .ledger import BudgetRefused
    from .segments import split_paragraph
    from .text.normalize import normalize

    final = load_lexicon(serie, lexiques).apply(normalize(phrase))
    pieces = split_paragraph(final, engine.max_chars)
    chars = sum(len(p) for p in pieces)
    lim = compute_limits(engine, chars, opts, ledger)
    log(f"Texte envoyé au moteur ({chars} caractères) :\n  {final}")
    for ln in describe_limits(engine, lim, chars):
        log(ln)
    try:
        engine.check_available()
    except EngineUnavailable as exc:
        raise BuildRefused(str(exc)) from exc
    out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        wavs: list[Path] = []
        for i, piece in enumerate(pieces):
            month = None
            if lim.paid:
                assert ledger is not None and lim.max_total is not None
                try:
                    month = ledger.reserve(engine.name, len(piece), lim.max_total)
                except BudgetRefused as exc:
                    raise BuildRefused(str(exc)) from exc
            try:
                wav = engine.synthesize(piece, voice, params or {}, Path(tmp) / f"s{i:03d}.wav")
            except EngineUnavailable as exc:
                raise BuildRefused(str(exc)) from exc
            if month is not None and ledger is not None:
                ledger.confirm(engine.name, len(piece), month)
            wavs.append(wav)
        concat_and_encode(wavs, out, silence_s=0.3)
    return out
