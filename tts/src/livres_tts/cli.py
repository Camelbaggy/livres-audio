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
    "estimate": "T3",
    "say": "T2",
    "build": "T2",
    "fix": "T2",
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
    pr.set_defaults(func=_prepare)

    e = sub.add_parser("moteurs", help="lister les moteurs et leur disponibilité")
    e.set_defaults(func=_engines)

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
