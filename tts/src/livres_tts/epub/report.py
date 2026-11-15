"""Rapport HTML de préparation (lisible dans un navigateur, sans dépendance)."""

from __future__ import annotations

from collections import defaultdict
from html import escape

from .clean import Removal

MAX_LISTED_PER_TYPE = 60
NNBSP = "\u202f"

_CSS = """
body{font:16px/1.5 system-ui,sans-serif;max-width:980px;margin:2rem auto;padding:0 1rem;color:#222}
h1,h2{line-height:1.2} table{border-collapse:collapse;width:100%;margin:1rem 0}
th,td{border-bottom:1px solid #ddd;padding:.3rem .5rem;text-align:left;vertical-align:top}
td.n{text-align:right;white-space:nowrap} .warn{background:#fff3cd;padding:.5rem 1rem}
.ex{font-family:ui-monospace,monospace;font-size:.9em;color:#444}
.before{background:#fdecea;padding:.2rem .4rem} .after{background:#e8f5e9;padding:.2rem .4rem}
details{margin:.4rem 0}
"""


def index_fmt(n: int) -> str:
    return format(n, ",").replace(",", NNBSP)


def format_duration(seconds: float) -> str:
    minutes = round(seconds / 60)
    hours, minutes = divmod(minutes, 60)
    return f"{hours} h {minutes:02d} min" if hours else f"{minutes} min"


def render_report(
    *,
    title: str,
    authors: list[str],
    source_name: str,
    chapters: list[dict],
    removals: list[Removal],
    samples: dict[int, list[tuple[str, str]]],
    warnings: list[str],
    total_chars: int,
    total_seconds: float,
    level: int,
    chapter_source: str,
    options: dict,
) -> str:
    e = escape
    out = [
        f"<!doctype html><html lang='fr'><meta charset='utf-8'><title>Rapport — {e(title)}</title>",
        f"<style>{_CSS}</style>",
        f"<h1>Rapport de préparation : {e(title)}</h1>",
        f"<p>{e(', '.join(authors))} — source : <code>{e(source_name)}</code></p>",
        "<h2>Résumé</h2><ul>",
        f"<li>{len(chapters)} chapitres, {index_fmt(total_chars)} caractères</li>",
        f"<li>Durée estimée : {format_duration(total_seconds)} (≈ 15 caractères par seconde)</li>",
        f"<li>Chapitrage : {e(chapter_source)}, niveau {level}</li>",
        f"<li>Options : {e(', '.join(f'{k}={v}' for k, v in options.items()))}</li>",
        f"<li>{len(removals)} passage(s) retiré(s) (détail plus bas)</li></ul>",
    ]
    if warnings:
        out.append("<div class='warn'><strong>Avertissements</strong><ul>")
        out.extend(f"<li>{e(w)}</li>" for w in warnings)
        out.append("</ul></div>")
    out.append(
        "<h2>Chapitres</h2><table><tr><th>N°</th><th>Titre</th>"
        "<th>Caractères</th><th>Durée estimée</th></tr>"
    )
    for ch in chapters:
        out.append(
            f"<tr><td class='n'>{ch['index']}</td><td>{e(ch['titre'])}</td>"
            f"<td class='n'>{index_fmt(ch['caracteres'])}</td>"
            f"<td class='n'>{format_duration(ch['duree_estimee_s'])}</td></tr>"
        )
    out.append("</table><h2>Passages retirés</h2>")
    by_kind: dict[str, list[Removal]] = defaultdict(list)
    for r in removals:
        by_kind[r.kind].append(r)
    if not by_kind:
        out.append("<p>Aucun.</p>")
    out.append("<table><tr><th>Type</th><th>Nombre</th></tr>")
    out.extend(
        f"<tr><td>{e(k)}</td><td class='n'>{len(v)}</td></tr>" for k, v in sorted(by_kind.items())
    )
    out.append("</table>")
    for kind, items in sorted(by_kind.items()):
        out.append(f"<details><summary>{e(kind)} ({len(items)})</summary><table>")
        out.append("<tr><th>Chapitre</th><th>Extrait</th></tr>")
        for r in items[:MAX_LISTED_PER_TYPE]:
            out.append(f"<tr><td>{e(r.chapter)}</td><td class='ex'>{e(r.excerpt)}</td></tr>")
        if len(items) > MAX_LISTED_PER_TYPE:
            more = len(items) - MAX_LISTED_PER_TYPE
            out.append(f"<tr><td colspan='2'>… et {more} autres</td></tr>")
        out.append("</table></details>")
    out.append("<h2>Avant / après normalisation</h2>")
    for ch in chapters:
        pairs = samples.get(ch["index"], [])
        if not pairs:
            continue
        out.append(f"<details><summary>{ch['index']}. {e(ch['titre'])}</summary>")
        for before, after in pairs:
            out.append(
                f"<p class='ex'><span class='before'>{e(before)}</span><br>"
                f"<span class='after'>{e(after)}</span></p>"
            )
        out.append("</details>")
    out.append("</html>")
    return "\n".join(out)
