"""Ligne de commande de l'atelier.

argparse (bibliothèque standard) plutôt que typer : aucune dépendance de plus,
et l'interface reste simple (quelques sous-commandes à options plates).
"""

from __future__ import annotations

import argparse
import sys
from datetime import datetime
from pathlib import Path

from . import __version__
from .bench import DEFAULT_BUDGET_MAX, PaidEngineRefused, parse_voices, run_bench
from .engines import EngineUnavailable, engine_names, get_engine

PENDING = {
    "publish": "T3",
}


def _bench(args: argparse.Namespace) -> int:
    text = Path(args.extrait).read_text("utf-8")
    names = [n.strip() for n in args.moteurs.split(",") if n.strip()]
    out_dir = (
        Path(args.sortie)
        if args.sortie
        else Path("bench-out") / datetime.now().strftime("%Y%m%d-%H%M%S")
    )
    try:
        result = run_bench(
            text,
            names,
            out_dir,
            voices=parse_voices(args.voix),
            allow_paid=args.autoriser_payant,
            budget_max=args.budget_max,
            seed=args.graine,
            log=lambda m: print(m, file=sys.stderr),
        )
    except PaidEngineRefused as exc:
        print(f"Refusé : {exc}", file=sys.stderr)
        return 2
    except (KeyError, ValueError) as exc:
        print(f"Erreur : {exc}", file=sys.stderr)
        return 2
    print(f"{len(result.entries)} voix générée(s) dans {out_dir}")
    for name, why in result.skipped.items():
        print(f"  ignoré : {name} — {why}")
    if result.entries:
        print(f"Ouvrir {out_dir / 'ecoute.html'} (ne pas ouvrir cle.json avant notation).")
        return 0
    return 1


def _prepare(args: argparse.Namespace) -> int:
    from .epub.extract import DrmError, EpubError
    from .epub.prepare import PrepareOptions, estimate_cost, prepare
    from .epub.report import format_duration

    options = PrepareOptions(
        keep_notes=args.garder_notes,
        chapter_level=args.niveau_chapitres,
        remove_dedications=args.retirer_dedicaces,
        exclude=[x for x in (args.exclure or "").split(",") if x.strip()],
    )
    try:
        result = prepare(
            Path(args.livre),
            Path(args.sortie) if args.sortie else None,
            options,
            log=lambda m: print(m, file=sys.stderr),
        )
    except DrmError as exc:
        print(f"Refusé : {exc}", file=sys.stderr)
        return 2
    except (EpubError, OSError) as exc:
        print(f"Erreur : {exc}", file=sys.stderr)
        return 2
    for warning in result.warnings:
        print(f"Attention : {warning}", file=sys.stderr)
    cost = estimate_cost(result.total_chars)
    print(f"Projet prêt : {result.project_dir}")
    chars = f"{result.total_chars:,}".replace(",", " ")
    print(
        f"{len(result.chapters)} chapitres, {chars} caractères, "
        f"durée estimée {format_duration(result.total_seconds)}."
    )
    print(
        f"Coût estimé avec {cost['moteur']} : {cost['cout_sans_palier']:.2f} € au tarif plein "
        f"({cost['prix_par_million']:.2f} €/M car.) ; {cost['cout_avec_palier']:.2f} € après "
        f"le palier gratuit mensuel de {cost['palier_gratuit']:,} caractères.".replace(",", " ")
    )
    print(f"Rapport à relire : {result.project_dir / 'rapport.html'}")
    return 0


def _settings(args: argparse.Namespace, project, engine, previous=None):
    from .project import BuildSettings, Silences

    prev = previous
    sil = Silences(**vars(prev.silences)) if prev else Silences()
    for opt, attr in (
        ("silence_segment", "segment"),
        ("silence_paragraphe", "paragraphe"),
        ("silence_scene", "scene"),
        ("silence_debut", "debut"),
        ("silence_fin", "fin"),
    ):
        val = getattr(args, opt, None)
        if val is not None:
            setattr(sil, attr, val)
    serie = getattr(args, "serie", None) or (prev.serie if prev else None)
    if serie is None:
        serie = project.data.get("livre", {}).get("serie")
        if serie:
            from .lexicon import default_lexicon_dir, slug

            given = getattr(args, "lexiques", None)
            lexdir = Path(given) if given else default_lexicon_dir()
            if not (lexdir / f"{slug(serie)}.yaml").exists():
                serie = None  # pas de lexique pour cette série : global seul
    lex = getattr(args, "lexiques", None) or (prev.lexiques if prev else None)
    voix = getattr(args, "voix", None) or (prev.voix if prev else None) or engine.default_voice
    return BuildSettings(
        moteur=engine.name,
        voix=voix,
        serie=serie,
        lexiques=str(Path(lex)) if lex else None,
        params=dict(prev.params) if prev else {},
        silences=sil,
    )


def _run_options(args: argparse.Namespace):
    from .build import RunOptions

    return RunOptions(
        budget_max=getattr(args, "budget_max", None),
        max_caracteres=getattr(args, "max_caracteres", None),
        jusqu_au_palier=getattr(args, "jusqu_au_palier", False),
        oui=getattr(args, "oui", False),
    )


def _build_like(args: argparse.Namespace, fix: bool) -> int:
    from .audio import FfmpegError
    from .build import BuildRefused, run
    from .ledger import Ledger
    from .lexicon import LexiconError
    from .project import ProjectError, load_project

    try:
        project = load_project(Path(args.projet))
        previous = project.load_settings()
        if fix and previous is None:
            print("Erreur : rien à corriger, aucun `build` n'a eu lieu dans ce projet.",
                  file=sys.stderr)
            return 2
        name = getattr(args, "moteur", None) or (previous.moteur if previous else None)
        if not name:
            print("Erreur : --moteur est requis.", file=sys.stderr)
            return 2
        engine = get_engine(name)
        if previous and previous.moteur != name:
            previous = None  # autre moteur : repartir de réglages neufs (clés différentes)
        settings = _settings(args, project, engine, previous)
        report = run(
            project, settings, engine, _run_options(args),
            ledger=Ledger(Path(args.registre) if args.registre else None), fix=fix,
        )  # fmt: skip
    except (BuildRefused, ProjectError, LexiconError, FfmpegError, KeyError) as exc:
        print(f"Refusé : {exc}", file=sys.stderr)
        return 2
    if report.aborted:
        return 1
    if report.synth.error:
        return 2
    if report.partial:
        return 4
    print(f"Terminé : {len(report.assembled)} chapitre(s) assemblé(s) dans {project.out_dir}")
    return 0


def _build(args: argparse.Namespace) -> int:
    return _build_like(args, fix=False)


def _fix(args: argparse.Namespace) -> int:
    return _build_like(args, fix=True)


def _estimate(args: argparse.Namespace) -> int:
    from .build import estimate
    from .ledger import Ledger
    from .lexicon import LexiconError
    from .project import ProjectError, load_project

    try:
        project = load_project(Path(args.projet))
        previous = project.load_settings()
        name = args.moteur or (previous.moteur if previous else None)
        if not name:
            print("Erreur : --moteur est requis.", file=sys.stderr)
            return 2
        engine = get_engine(name)
        if previous and previous.moteur != name:
            previous = None
        settings = _settings(args, project, engine, previous)
        for line in estimate(
            project, settings, engine, Ledger(Path(args.registre) if args.registre else None)
        ):
            print(line)
    except (ProjectError, LexiconError, KeyError) as exc:
        print(f"Erreur : {exc}", file=sys.stderr)
        return 2
    return 0


def _say(args: argparse.Namespace) -> int:
    from .audio import FfmpegError
    from .build import BuildRefused, say
    from .ledger import Ledger
    from .lexicon import LexiconError

    try:
        engine = get_engine(args.moteur)
        voix = args.voix or engine.default_voice
        out = Path(args.sortie) if args.sortie else Path("say-out") / "phrase.m4a"
        say(
            args.phrase, engine, voix, out,
            serie=args.serie,
            lexiques=Path(args.lexiques) if args.lexiques else None,
            opts=_run_options(args),
            ledger=Ledger(Path(args.registre) if args.registre else None),
        )  # fmt: skip
    except (BuildRefused, LexiconError, FfmpegError, KeyError) as exc:
        print(f"Refusé : {exc}", file=sys.stderr)
        return 2
    print(f"Écrit : {out}")
    return 0


def _engines(_args: argparse.Namespace) -> int:
    for name in engine_names():
        eng = get_engine(name)
        try:
            eng.check_available()
            state = "disponible"
        except EngineUnavailable as exc:
            state = f"indisponible ({exc})"
        kind = "payant" if eng.is_paid else "gratuit"
        print(f"{name:12} {kind:8} {state}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="tts", description="Atelier de synthèse vocale")
    p.add_argument("--version", action="version", version=f"tts {__version__}")
    sub = p.add_subparsers(dest="cmd", metavar="commande")

    b = sub.add_parser("bench", help="comparer des voix à l'aveugle sur un extrait")
    b.add_argument("extrait", help="fichier texte de l'extrait")
    b.add_argument(
        "--moteurs",
        required=True,
        help="liste séparée par des virgules ; « moteur:voix » pour choisir la voix",
    )
    b.add_argument("--voix", help="moteur=voix,moteur2=voix2")
    b.add_argument("--sortie", help="dossier de sortie (défaut : bench-out/<date>)")
    b.add_argument("--autoriser-payant", action="store_true", help="accepter un moteur payant")
    b.add_argument(
        "--budget-max",
        type=float,
        default=DEFAULT_BUDGET_MAX,
        help="plafond en euros du coût maximal estimé du banc (défaut 0,50)",
    )
    b.add_argument("--graine", type=int, help="graine de l'ordre aléatoire (tests)")
    b.set_defaults(func=_bench)

    pr = sub.add_parser("prepare", help="EPUB sans DRM → projet de travail (texte par chapitre)")
    pr.add_argument("livre", help="fichier EPUB sans DRM")
    pr.add_argument("--sortie", help="dossier du projet (défaut : projets/<nom du fichier>)")
    pr.add_argument("--garder-notes", action="store_true", help="lire les notes en fin de chapitre")
    pr.add_argument(
        "--niveau-chapitres",
        type=int,
        help="niveau de la table des matières qui donne les chapitres (défaut : automatique)",
    )
    pr.add_argument("--retirer-dedicaces", action="store_true", help="ne pas lire les dédicaces")
    pr.add_argument(
        "--exclure",
        help="chapitres à ne pas lire : numéros ou extraits de titre séparés par des virgules "
        "(ex. « glossaire,notes de l'auteur »)",
    )
    pr.set_defaults(func=_prepare)

    e = sub.add_parser("moteurs", help="lister les moteurs et leur disponibilité")
    e.set_defaults(func=_engines)

    def common(sp: argparse.ArgumentParser) -> None:
        sp.add_argument("--lexiques", help="dossier des lexiques (défaut : tts/lexiques)")
        sp.add_argument("--registre", help="fichier du registre des dépenses (défaut : config)")

    def costs(sp: argparse.ArgumentParser) -> None:
        sp.add_argument(
            "--budget-max", type=float,
            help="plafond en euros au-delà du palier gratuit du mois, pour ce run (arrêt net)",
        )  # fmt: skip
        sp.add_argument("--max-caracteres", type=int, help="limite de caractères pour ce run")
        sp.add_argument(
            "--jusqu-au-palier", action="store_true",
            help="sans --budget-max : synthétiser jusqu'à la fin du palier gratuit puis s'arrêter",
        )  # fmt: skip
        sp.add_argument("--oui", action="store_true", help="ne pas demander de confirmation")

    def silences(sp: argparse.ArgumentParser) -> None:
        for opt, hlp in (
            ("segment", "entre deux segments d'un paragraphe (0,3 s)"),
            ("paragraphe", "entre paragraphes (0,6 s)"),
            ("scene", "changement de scène « * * * » (1,5 s)"),
            ("debut", "début de chapitre (1 s)"),
            ("fin", "fin de chapitre (2 s)"),
        ):
            sp.add_argument(f"--silence-{opt}", type=float, help=hlp)

    say_p = sub.add_parser("say", help="synthétiser une phrase (vérifier une prononciation)")
    say_p.add_argument("phrase")
    say_p.add_argument("--moteur", required=True)
    say_p.add_argument("--voix")
    say_p.add_argument("--serie", help="lexique de série (tts/lexiques/<serie>.yaml)")
    say_p.add_argument("--sortie", help="fichier .m4a (défaut : say-out/phrase.m4a)")
    common(say_p)
    costs(say_p)
    say_p.set_defaults(func=_say)

    bu = sub.add_parser("build", help="synthèse (reprise possible) + assemblage en M4A")
    bu.add_argument("projet")
    bu.add_argument("--moteur")
    bu.add_argument("--voix")
    bu.add_argument("--serie", help="lexique de série (défaut : série du livre si lexique)")
    common(bu)
    costs(bu)
    silences(bu)
    bu.set_defaults(func=_build)

    fx = sub.add_parser("fix", help="regénérer seulement ce qu'un changement de lexique modifie")
    fx.add_argument("projet")
    common(fx)
    costs(fx)
    silences(fx)
    fx.set_defaults(func=_fix)

    es = sub.add_parser("estimate", help="caractères, durée, coût et palier gratuit du mois")
    es.add_argument("projet")
    es.add_argument("--moteur")
    es.add_argument("--voix")
    es.add_argument("--serie")
    common(es)
    es.set_defaults(func=_estimate)

    for name, lot in PENDING.items():
        sp = sub.add_parser(name, help=f"pas encore disponible (lot {lot})")
        sp.add_argument("args", nargs="*")
        sp.set_defaults(func=lambda _a, n=name, lot=lot: _pending(n, lot))
    return p


def _pending(name: str, lot: str) -> int:
    print(f"tts {name} : pas encore disponible (lot {lot})", file=sys.stderr)
    return 3


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if not getattr(args, "func", None):
        parser.print_help()
        return 0
    return args.func(args)


def entry() -> None:
    sys.exit(main())


if __name__ == "__main__":
    entry()
