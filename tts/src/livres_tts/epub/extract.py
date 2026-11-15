"""Extraction : métadonnées, ordre de lecture, table des matières, couverture.

Refuse les EPUB protégés (DRM). `ebooklib` fait la lecture du conteneur et de la table des
matières (NCX pour EPUB 2, `nav` pour EPUB 3) ; l'analyse du contenu HTML est faite plus loin.
"""

from __future__ import annotations

import posixpath
import re
import warnings
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

import ebooklib
from ebooklib import epub
from lxml import etree


class EpubError(Exception):
    """Fichier EPUB illisible."""


class DrmError(EpubError):
    """EPUB protégé : l'outil refuse de le traiter."""


# Algorithmes d'obfuscation de polices : ce n'est pas du DRM, les textes restent lisibles.
FONT_OBFUSCATION = {
    "http://www.idpf.org/2008/embedding",
    "http://ns.adobe.com/pdf/enc#RC",
}
_DRM_FILES = ("META-INF/rights.xml", "META-INF/sinf.xml", "META-INF/license.lcpl")


@dataclass
class TocEntry:
    title: str
    path: str  # chemin du fichier dans l'EPUB, relatif au dossier de l'OPF (comme le spine)
    fragment: str | None
    level: int  # 1 = premier niveau


@dataclass
class SpineDoc:
    path: str
    html: bytes
    linear: bool = True


@dataclass
class RawBook:
    source: Path
    title: str
    authors: list[str]
    language: str
    year: int | None
    series: str | None
    series_index: str | None
    spine: list[SpineDoc]
    toc: list[TocEntry]
    cover: tuple[bytes, str] | None = None  # (octets, extension : "jpg" ou "png")
    warnings: list[str] = field(default_factory=list)


def check_drm(path: Path) -> None:
    """Lève DrmError si l'EPUB est chiffré autrement que par l'obfuscation de polices."""
    try:
        zf = zipfile.ZipFile(path)
    except (zipfile.BadZipFile, OSError) as exc:
        raise EpubError(f"{path.name} n'est pas un EPUB lisible ({exc})") from exc
    with zf:
        names = set(zf.namelist())
        for name in _DRM_FILES:
            if name in names:
                raise DrmError(
                    f"{path.name} est protégé par un DRM ({name}) : l'atelier ne traite que "
                    "les EPUB sans DRM."
                )
        if "META-INF/encryption.xml" not in names:
            return
        try:
            root = etree.fromstring(zf.read("META-INF/encryption.xml"))
        except etree.XMLSyntaxError as exc:
            raise EpubError("META-INF/encryption.xml illisible") from exc
        protected: list[str] = []
        for data in root.iter("{*}EncryptedData"):
            method = data.find("{*}EncryptionMethod")
            algorithm = method.get("Algorithm", "") if method is not None else ""
            ref = data.find(".//{*}CipherReference")
            uri = ref.get("URI", "") if ref is not None else ""
            if algorithm not in FONT_OBFUSCATION:
                protected.append(uri or algorithm or "?")
        if protected:
            sample = ", ".join(protected[:3])
            raise DrmError(
                f"{path.name} est chiffré (DRM) : {len(protected)} ressource(s) protégée(s) "
                f"({sample}). L'atelier ne traite que les EPUB sans DRM."
            )


def _split_href(href: str, base_dir: str = "") -> tuple[str, str | None]:
    path, _, fragment = href.partition("#")
    path = posixpath.normpath(posixpath.join(base_dir, path)) if path else ""
    return path, (fragment or None)


def _flatten_toc(nodes: object, level: int, out: list[TocEntry]) -> None:
    for node in nodes if isinstance(nodes, (list, tuple)) else [nodes]:
        children: object = []
        if isinstance(node, tuple):
            node, children = node
        href = getattr(node, "href", "") or ""
        title = re.sub(r"\s+", " ", getattr(node, "title", "") or "").strip()
        path, fragment = _split_href(href)
        index = len(out)
        out.append(TocEntry(title, path, fragment, level))
        _flatten_toc(children, level + 1, out)
        if not path and len(out) > index + 1:  # section sans lien : prend celui de son 1er enfant
            out[index].path, out[index].fragment = out[index + 1].path, out[index + 1].fragment


def _meta(book: epub.EpubBook, ns: str, name: str) -> list[str]:
    return [v for v, _attrs in book.get_metadata(ns, name) if v]


def _opf_meta(book: epub.EpubBook, key: str) -> str | None:
    for _value, attrs in book.get_metadata("OPF", "meta"):
        if attrs.get("name") == key and attrs.get("content"):
            return attrs["content"]
    return None


def _find_cover(book: epub.EpubBook) -> tuple[bytes, str] | None:
    candidates: list[object] = []
    cover_id = _opf_meta(book, "cover")
    if cover_id:
        candidates.append(book.get_item_with_id(cover_id))
    candidates.extend(book.get_items_of_type(ebooklib.ITEM_COVER))
    candidates.extend(
        item
        for item in book.get_items_of_type(ebooklib.ITEM_IMAGE)
        if re.search(r"cover|couv", item.get_name(), re.I)
    )
    for item in candidates:
        if item is None:
            continue
        data = item.get_content()
        if data[:3] == b"\xff\xd8\xff":
            return data, "jpg"
        if data[:8] == b"\x89PNG\r\n\x1a\n":
            return data, "png"
    return None


def load_epub(path: Path) -> RawBook:
    """Lit l'EPUB. Lève DrmError (protégé) ou EpubError (illisible)."""
    path = Path(path)
    check_drm(path)
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            book = epub.read_epub(str(path), options={"ignore_ncx": False})
    except Exception as exc:  # ebooklib lève des exceptions variées sur un fichier invalide
        raise EpubError(f"{path.name} : lecture impossible ({exc})") from exc

    spine: list[SpineDoc] = []
    for idref, linear in book.spine:
        item = book.get_item_with_id(idref)
        if item is None or item.get_type() not in (ebooklib.ITEM_DOCUMENT, ebooklib.ITEM_UNKNOWN):
            continue
        if not item.get_name().lower().endswith((".xhtml", ".html", ".htm", ".xml")):
            continue
        spine.append(SpineDoc(item.get_name(), item.get_content(), str(linear).lower() != "no"))

    toc: list[TocEntry] = []
    _flatten_toc(book.toc, 1, toc)
    toc = [e for e in toc if e.path]

    titles = _meta(book, "DC", "title")
    date = next(iter(_meta(book, "DC", "date")), "")
    year_match = re.match(r"\s*(\d{4})", date)
    raw = RawBook(
        source=path,
        title=titles[0].strip() if titles else path.stem,
        authors=[a.strip() for a in _meta(book, "DC", "creator")],
        language=(next(iter(_meta(book, "DC", "language")), "") or "").strip(),
        year=int(year_match[1]) if year_match else None,
        series=_opf_meta(book, "calibre:series"),
        series_index=_opf_meta(book, "calibre:series_index"),
        spine=spine,
        toc=toc,
        cover=_find_cover(book),
    )
    if not spine:
        raise EpubError(f"{path.name} : aucun document dans l'ordre de lecture")
    if raw.language and not raw.language.lower().startswith("fr"):
        raw.warnings.append(
            f"langue du livre « {raw.language} » : "
            "la normalisation du texte est conçue pour le français"
        )
    return raw
