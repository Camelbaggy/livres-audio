"""Fabrique d'EPUB synthétiques pour les tests (contenu entièrement inventé)."""

from __future__ import annotations

import re
import zipfile
from dataclasses import dataclass
from pathlib import Path

from ebooklib import epub

SENTENCES = [
    "Le chevalier de Valcombe chevauchait depuis l'aube sur la route de Montferrand.",
    "« Où allez-vous ainsi, Monsieur ? » demanda la vieille Hortense en essuyant ses mains.",
    "Il ne répondit pas et regarda les collines que le brouillard dévorait lentement.",
    "— Nous serons à Mirecourt avant la nuit, dit-il enfin, si le gué n'est pas trop haut.",
    "Au XVIe siècle, disait-on, les routes de la vallée étaient plus sûres qu'aujourd'hui.",
    "Le roi Henri IV, qu'il avait connu jeune, aimait à répéter que le pain valait mieux que l'or.",
    "On comptait vingt-deux métayers sur les terres du domaine, et trois cents brebis.",
    "Sa mère, Mme de Valcombe, tenait registre de chaque sac de blé depuis le 14 mars 1571.",
    "Le soir tombait quand ils aperçurent enfin les tours de la forteresse.",
    "Hortense posa la lampe sur la table et soupira longuement, comme on soupire d'un vieux chagrin.",
]


def paragraphs(count: int, seed: int = 0) -> list[str]:
    """`count` paragraphes (≈ 250 caractères chacun), déterministes."""
    out = []
    for i in range(count):
        k = (seed + i) % len(SENTENCES)
        out.append(" ".join(SENTENCES[(k + j) % len(SENTENCES)] for j in range(3)))
    return out


def p(text: str) -> str:
    return f"<p>{text}</p>"


def body_of(count: int, seed: int = 0) -> str:
    return "\n".join(p(t) for t in paragraphs(count, seed))


@dataclass
class Doc:
    name: str
    title: str
    body: str
    linear: bool = True


TocNode = tuple[str, str, list["TocNode"]]  # (titre, href, enfants)


def _toc(nodes: list[TocNode], counter: list[int]):
    out = []
    for title, href, children in nodes:
        counter[0] += 1
        link = epub.Link(href, title, f"t{counter[0]}")
        if children:
            out.append((epub.Section(title, href=href), _toc(children, counter)))
        else:
            out.append(link)
    return out


def build_epub(
    path: Path,
    docs: list[Doc],
    toc: list[TocNode],
    *,
    version: int = 2,
    title: str = "Les Seigneurs de Valcombe",
    author: str = "Jeanne Dupré-Marsan",
    cover: bool = False,
    series: tuple[str, str] | None = None,
    language: str = "fr",
) -> Path:
    book = epub.EpubBook()
    book.set_identifier("urn:uuid:00000000-0000-0000-0000-000000000001")
    book.set_title(title)
    book.set_language(language)
    book.add_author(author)
    book.add_metadata("DC", "date", "2024-05-01")
    if series:
        book.add_metadata(None, "meta", "", {"name": "calibre:series", "content": series[0]})
        book.add_metadata(None, "meta", "", {"name": "calibre:series_index", "content": series[1]})
    items = []
    for d in docs:
        item = epub.EpubHtml(title=d.title, file_name=d.name, lang="fr")
        item.content = d.body
        book.add_item(item)
        items.append((item, d.linear))
    spine: list = []
    if cover:
        book.set_cover("images/cover.jpg", b"\xff\xd8\xff\xe0fake-jpeg-bytes")
        spine.append("cover")
    if version == 2:
        book.add_item(epub.EpubNcx())
    else:
        spine.append("nav")
    book.add_item(epub.EpubNav())
    book.toc = _toc(toc, [0])
    spine.extend((item, "yes" if linear else "no") for item, linear in items)
    book.spine = spine
    epub.write_epub(str(path), book)
    if version == 3:  # EPUB 3 sans NCX : on retire la référence que ebooklib écrit toujours
        rewrite_zip(path, {"EPUB/content.opf": lambda b: b.replace(b' toc="ncx"', b"")})
    return path


def rewrite_zip(path: Path, changes: dict, extra: dict[str, bytes] | None = None) -> None:
    """Réécrit l'archive : `changes` transforme des fichiers, `extra` en ajoute."""
    tmp = path.with_suffix(".tmp")
    with zipfile.ZipFile(path) as src, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            data = src.read(info.filename)
            if info.filename in changes:
                data = changes[info.filename](data)
            dst.writestr(info, data)
        for name, data in (extra or {}).items():
            dst.writestr(name, data)
    tmp.replace(path)


ENCRYPTION_XML = """<?xml version="1.0"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
            xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
  <enc:EncryptedData>
    <enc:EncryptionMethod Algorithm="{algorithm}"/>
    <enc:CipherData><enc:CipherReference URI="{uri}"/></enc:CipherData>
  </enc:EncryptedData>
</encryption>"""


def add_encryption(path: Path, algorithm: str, uri: str = "EPUB/ch1.xhtml") -> None:
    xml = ENCRYPTION_XML.format(algorithm=algorithm, uri=uri).encode()
    rewrite_zip(path, {}, {"META-INF/encryption.xml": xml})


def strip_ids(text: str) -> str:
    return re.sub(r"\s+", " ", text)
