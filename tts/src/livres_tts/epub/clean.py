"""Nettoyage du contenu HTML d'un EPUB : du HTML au texte, par blocs.

Chaque passage retiré est consigné (`Removal`) avec son type, son emplacement et un extrait de
80 caractères, pour le rapport de contrôle. Les fonctions de décision sur du texte
(`is_page_number`, `is_legal_notice`, `join_lines`, `running_head_keys`…) sont pures.
"""

from __future__ import annotations

import re
import warnings
from collections import Counter
from dataclasses import dataclass, field

from bs4 import BeautifulSoup, Comment, NavigableString, Tag, XMLParsedAsHTMLWarning

# Types de retrait (libellés du rapport)
R_NOTE_CALL = "appel de note"
R_NOTE_BODY = "corps de note"
R_SUP = "exposant numérique isolé"
R_PAGE = "numéro de page"
R_HEADER = "en-tête ou pied de page répété"
R_TITLEPAGE = "page de titre"
R_LEGAL = "mentions légales"
R_TOC = "sommaire"
R_DEDICATION = "dédicace"
R_COVER = "couverture"
R_EMPTY = "page sans texte"
R_HYPHEN = "césure"
R_SOFT = "trait d'union conditionnel"
R_LEADING = "page liminaire non référencée"
R_NOTES_DOC = "page de notes"

MARKER_OPEN, MARKER_CLOSE = "", ""
_MARKER_RE = re.compile(f"{MARKER_OPEN}(\\d+){MARKER_CLOSE}")

NOTE_TYPES = {"footnote", "endnote", "rearnote", "note"}
NOTE_CONTAINER_TYPES = {"footnotes", "endnotes", "rearnotes", "notes"}
_DOC_TYPES = {
    "titlepage": R_TITLEPAGE, "halftitlepage": R_TITLEPAGE, "fulltitle": R_TITLEPAGE,
    "copyright-page": R_LEGAL, "colophon": R_LEGAL, "imprint": R_LEGAL, "legalnotice": R_LEGAL,
    "toc": R_TOC, "landmarks": R_TOC, "loi": R_TOC, "lot": R_TOC, "page-list": R_TOC,
    "dedication": R_DEDICATION, "cover": R_COVER,
    "footnotes": R_NOTES_DOC, "endnotes": R_NOTES_DOC, "rearnotes": R_NOTES_DOC,
    "notes": R_NOTES_DOC,
}  # fmt: skip

BLOCK_TAGS = {
    "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "dt", "dd", "blockquote", "pre", "figcaption",
    "caption", "div", "section", "article", "aside", "header", "footer", "main", "ul", "ol", "dl",
    "table", "tr", "td", "th", "tbody", "thead", "figure", "nav", "hgroup", "details", "summary",
    "address", "body", "tfoot", "center",
}  # fmt: skip
_SKIP_TAGS = {"script", "style", "head", "title", "meta", "link", "img", "svg", "image", "audio",
              "video", "object", "canvas", "iframe", "math", "noscript"}  # fmt: skip

_NOTE_CALL_TEXT = re.compile(r"^\s*[\[(]?\s*(\d{1,3}|[*†‡§]+|[a-z])\s*[\])]?\s*$")
_NOTE_CLASS = re.compile(r"note|footnote|fn\b|appel|renvoi|noteref", re.I)
_PAGE_CLASS = re.compile(r"pagebreak|pagenum|page-num|pageno|page_num|folio", re.I)
_PAGE_NUMBER = re.compile(r"^\s*[-–—]?\s*\d{1,3}\s*[-–—]?\s*$")  # 4 chiffres : plutôt une année
_LEGAL = re.compile(
    r"©|\bISBN\b|tous droits réservés|dépôt légal|\bcopyright\b|\bAll rights reserved\b|"
    r"loi du 11 mars 1957|reproduction interdite",
    re.I,
)
_TITLEPAGE_NAME = re.compile(
    r"(^|[/_.-])(title|titre|titlepage|page[-_]?titre|halftitle)(?=[/_.-]|$)", re.I
)
_COVER_NAME = re.compile(r"(^|[/_.-])(cover|couv|couverture)(?=[/_.-]|\d|$)", re.I)
_TOC_HEADING = re.compile(
    r"^\s*(table des mati[èe]res|sommaire|contents|table of contents)\s*$", re.I
)
_DIALOGUE_START = re.compile(r"^[-–—―]\s")
# Mots composés dont le trait d'union tombe parfois en fin de ligne : on ne les « recolle » pas.
_COMPOUND_LEFT = {
    "peut", "vis", "arc", "porte", "grand", "petit", "ci", "là", "tout", "long", "demi",
    "quelques", "après", "avant", "contre", "sous", "chef", "belle", "beau", "mi", "non",
}  # fmt: skip
_HYPHEN_JOIN = re.compile(r"([A-Za-zÀ-ÿœŒ]{2,})-$")
SOFT_HYPHENS = "­"
_INVISIBLE = dict.fromkeys(map(ord, "­​‌‍﻿⁠"))

MIN_LEADING_CHARS = 800


@dataclass
class Block:
    kind: str  # "p" ou "h"
    text: str
    level: int = 0  # niveau de titre (1-6) si kind == "h"
    anchors: tuple[str, ...] = ()
    notes: list[str] = field(default_factory=list)  # textes des notes appelées dans ce bloc


@dataclass
class Removal:
    kind: str
    source: str  # chemin du document
    excerpt: str
    position: int = 0  # nombre de blocs conservés qui précèdent, dans ce document
    note: str | None = None  # (appels de note) texte de la note, pour --garder-notes
    chapter: str = ""  # renseigné lors du chapitrage


@dataclass
class DocResult:
    path: str
    blocks: list[Block] = field(default_factory=list)
    removals: list[Removal] = field(default_factory=list)
    removed_as: str | None = None  # document entièrement retiré (type du retrait)
    note_bodies: dict[str, str] = field(default_factory=dict)


@dataclass
class NoteIndex:
    targets: set[str] = field(default_factory=set)  # ids visés par des appels de note
    bodies: dict[str, str] = field(default_factory=dict)  # id -> texte de la note


@dataclass
class CleanOptions:
    keep_notes: bool = False
    remove_dedications: bool = False


# --- fonctions pures sur du texte ----------------------------------------------------


def collapse(text: str) -> str:
    return re.sub(r"\s+", " ", text.translate(_INVISIBLE)).strip()


def excerpt(text: str, size: int = 80) -> str:
    text = collapse(text)
    return text if len(text) <= size else text[: size - 1] + "…"


def is_page_number(text: str) -> bool:
    return bool(_PAGE_NUMBER.match(text))


def is_legal_notice(text: str) -> bool:
    return len(text) < 500 and bool(_LEGAL.search(text))


def join_lines(raw: str) -> tuple[list[str], list[str]]:
    """Recolle les lignes d'un même paragraphe.

    Renvoie (paragraphes, césures recollées). Une ligne qui commence par un tiret de dialogue
    ouvre un nouveau paragraphe ; un mot coupé par un trait d'union en fin de ligne est recollé
    (sauf pour une courte liste de mots composés).
    """
    parts = [p.strip() for p in raw.translate(_INVISIBLE).split("\n")]
    parts = [re.sub(r"[ \t\r\f\v]+", " ", p) for p in parts if p]
    paragraphs: list[str] = []
    joined: list[str] = []
    current = ""
    for part in parts:
        if not current:
            current = part
            continue
        if _DIALOGUE_START.match(part):
            paragraphs.append(current)
            current = part
            continue
        m = _HYPHEN_JOIN.search(current)
        if m and part[0].islower():
            if m[1].lower() in _COMPOUND_LEFT:
                current += part  # mot composé : le trait d'union est conservé
            else:
                joined.append(f"{m[1]}-/{part.split(' ', 1)[0]}")
                current = current[:-1] + part
        else:
            current += " " + part
    if current:
        paragraphs.append(current)
    return paragraphs, joined


def running_head_keys(texts: list[str], min_count: int = 5) -> set[str]:
    """Clés des paragraphes courts, sans ponctuation finale, répétés (en-têtes, pieds de page).

    La clé est le texte en minuscules sans chiffres : « Mespech 12 » et « Mespech 13 » sont
    le même en-tête. Une réplique (« — Oui. ») ou un séparateur (« * * * ») n'en est pas un.
    """
    counts: Counter[str] = Counter()
    for text in texts:
        key = running_head_key(text)
        if key:
            counts[key] += 1
    return {k for k, n in counts.items() if n >= min_count}


def running_head_key(text: str) -> str | None:
    text = collapse(text)
    if not text or len(text) > 60 or text[-1] in '.!?…»,;:"”)':
        return None
    if _DIALOGUE_START.match(text) or text[0] in '«"“(':
        return None
    key = re.sub(r"\d+", "", text).lower().strip(" -–—|·.")
    return key if re.search(r"[a-zà-ÿ]", key) else None


# --- HTML ------------------------------------------------------------------------------


def _soup(html: bytes) -> BeautifulSoup:
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", XMLParsedAsHTMLWarning)  # XHTML lu avec l'analyseur HTML
        return BeautifulSoup(html, "lxml")


def _types(tag: Tag) -> set[str]:
    out: set[str] = set()
    for attr in ("epub:type", "role"):
        value = tag.attrs.get(attr)
        if isinstance(value, str):
            out.update(t.removeprefix("doc-") for t in value.split())
    return out


def _classes(tag: Tag) -> str:
    cls = tag.attrs.get("class") or []
    return " ".join(cls) if isinstance(cls, list) else str(cls)


def _is_noteref(a: Tag) -> bool:
    if "noteref" in _types(a):
        return True
    href = a.attrs.get("href") or ""
    if "#" not in href or not _NOTE_CALL_TEXT.match(a.get_text()):
        return False
    parent = a.parent
    return bool(
        (isinstance(parent, Tag) and parent.name in ("sup", "sub"))
        or _NOTE_CLASS.search(_classes(a))
        or (isinstance(parent, Tag) and _NOTE_CLASS.search(_classes(parent)))
    )


def _note_container(el: Tag) -> Tag:
    """Remonte d'un élément inline (ancre) jusqu'au bloc qui est la note."""
    block_like = {"li", "p", "div", "aside", "dd", "section", "td", "blockquote"}
    cur: Tag = el
    while cur.name not in block_like and isinstance(cur.parent, Tag):
        cur = cur.parent
    return cur


def _note_text(el: Tag) -> str:
    text = collapse(el.get_text(" "))
    text = re.sub(r"^[\[(]?\s*(\d{1,3}|[*†‡§]+)\s*[\])]?[.:]?\s+", "", text)
    return re.sub(r"\s*[↩↑⮌↵]\s*$", "", text).strip()


def _doc_types(soup: BeautifulSoup) -> set[str]:
    """Types epub:type de la racine du contenu (body, et enveloppes à enfant unique)."""
    types: set[str] = set()
    node: Tag | None = soup.body
    for _ in range(3):
        if node is None:
            break
        types |= _types(node)
        children = [c for c in node.children if isinstance(c, Tag)]
        node = children[0] if len(children) == 1 else None
    return types


def classify_document(soup: BeautifulSoup, path: str, options: CleanOptions) -> str | None:
    """Type de retrait d'un document entier (page de titre, sommaire…), ou None."""
    text = collapse((soup.body or soup).get_text(" "))
    for t in _doc_types(soup):
        kind = _DOC_TYPES.get(t)
        if kind == R_DEDICATION and not options.remove_dedications:
            continue
        if kind:
            return kind
    if not text:
        return R_EMPTY
    name = path.rsplit("/", 1)[-1]
    if len(text) < 600 and _TITLEPAGE_NAME.search(name):
        return R_TITLEPAGE
    if len(text) < 600 and _COVER_NAME.search(name):
        return R_COVER
    if len(text) < 2500 and _LEGAL.search(text):
        return R_LEGAL
    heading = soup.find(["h1", "h2", "h3", "p"])
    if heading is not None and _TOC_HEADING.match(heading.get_text()):
        links = soup.find_all("a")
        if len(links) >= 3:
            return R_TOC
    if options.remove_dedications and len(text) < 600 and re.search(r"dedic|dédic", name, re.I):
        return R_DEDICATION
    return None


def collect_notes(docs: list[tuple[str, bytes]], options: CleanOptions) -> NoteIndex:
    """Première passe : cibles des appels de note et texte des notes, sur tout le livre."""
    index = NoteIndex()
    soups: list[BeautifulSoup] = []
    for path, html in docs:
        soup = _soup(html)
        kind = classify_document(soup, path, options)
        if kind in (R_TOC, R_TITLEPAGE, R_LEGAL, R_COVER, R_EMPTY):
            continue
        soups.append(soup)
        for a in soup.find_all("a"):
            if _is_noteref(a):
                index.targets.add((a.attrs.get("href") or "").rsplit("#", 1)[-1])
    for soup in soups:
        for el in soup.find_all(id=True):
            if el.attrs["id"] in index.targets or _types(el) & NOTE_TYPES:
                container = _note_container(el)
                index.bodies.setdefault(el.attrs["id"], _note_text(container))
    return index


class _Cleaner:
    def __init__(self, path: str, soup: BeautifulSoup, notes: NoteIndex, options: CleanOptions):
        self.path, self.soup, self.notes, self.options = path, soup, notes, options
        self.result = DocResult(path)
        self.pending_ids: list[str] = []
        self.soft_hyphens = 0

    # -- retraits ---------------------------------------------------------------------

    def _mark(self, el: Tag, kind: str, note: str | None = None) -> None:
        idx = len(self.result.removals)
        shown = el.get_text(" ")
        if note:
            shown = f"[{collapse(shown)}] {note}"  # appel de note : on montre la note visée
        self.result.removals.append(Removal(kind, self.path, excerpt(shown), note=note))
        el.replace_with(NavigableString(f"{MARKER_OPEN}{idx}{MARKER_CLOSE}"))

    def preprocess(self) -> None:
        soup = self.soup
        for el in soup.find_all(["script", "style", "head"]):
            el.decompose()
        for c in soup.find_all(string=lambda s: isinstance(s, Comment)):
            c.extract()
        for nav in soup.find_all("nav"):
            if not nav.parent:
                continue
            self._mark(nav, R_TOC)
        for el in soup.find_all(True):
            if el.parent is None:
                continue
            if _types(el) & {"pagebreak"} or _PAGE_CLASS.search(_classes(el)):
                self._mark(el, R_PAGE)
        for el in list(soup.find_all(True)):
            if el.parent is None:
                continue
            ident = el.attrs.get("id")
            types = _types(el)
            if (
                types & NOTE_CONTAINER_TYPES
                or types & NOTE_TYPES
                or (ident in self.notes.targets and el.name not in ("a", "span", "sup"))
            ):
                self._mark(el, R_NOTE_BODY)
            elif ident in self.notes.targets:  # ancre inline : c'est son bloc qui est la note
                container = _note_container(el)
                if container is not soup.body and container.parent is not None:
                    self._mark(container, R_NOTE_BODY)
        for a in list(soup.find_all("a")):
            if a.parent is not None and _is_noteref(a):
                target = (a.attrs.get("href") or "").rsplit("#", 1)[-1]
                node: Tag = a
                parent = a.parent
                if (
                    isinstance(parent, Tag)
                    and parent.name in ("sup", "sub")
                    and not collapse(parent.get_text().replace(a.get_text(), "", 1))
                ):
                    node = parent
                self._mark(node, R_NOTE_CALL, note=self.notes.bodies.get(target))
        for sup in list(soup.find_all("sup")):
            if sup.parent is None:
                continue
            if re.fullmatch(r"\s*[\[(]?\d{1,3}[\])]?\s*", sup.get_text()):
                prev = sup.previous_sibling
                if isinstance(prev, NavigableString) and re.search(r"\d$", str(prev)):
                    power = sup.get_text().strip("[]() ")
                    sup.replace_with(NavigableString(f" puissance {power} "))
                    continue  # 10<sup>3</sup> : exposant mathématique, lu « dix puissance trois »
                self._mark(sup, R_SUP)

    # -- extraction des blocs -----------------------------------------------------------

    def _emit(self, kind: str, level: int, raw_text: str, ids: list[str]) -> None:
        notes: list[str] = []
        self.soft_hyphens += raw_text.count(SOFT_HYPHENS)

        def take_marker(m: re.Match[str]) -> str:
            rem = self.result.removals[int(m[1])]
            rem.position = len(self.result.blocks)
            if rem.note and rem.kind == R_NOTE_CALL:
                notes.append(rem.note)
            return " "

        text = _MARKER_RE.sub(take_marker, raw_text)
        paragraphs, hyphens = join_lines(text)
        for h in hyphens:
            self.result.removals.append(
                Removal(R_HYPHEN, self.path, h, position=len(self.result.blocks))
            )
        if not paragraphs:
            self.pending_ids.extend(ids)
            return
        for i, para in enumerate(paragraphs):
            para = collapse(para)
            if not para:
                continue
            anchors = tuple(self.pending_ids + ids) if i == 0 else ()
            if kind == "p" and is_page_number(para):
                self.result.removals.append(
                    Removal(R_PAGE, self.path, excerpt(para), position=len(self.result.blocks))
                )
                self.pending_ids.extend(ids)
                continue
            if kind == "p" and is_legal_notice(para):
                self.result.removals.append(
                    Removal(R_LEGAL, self.path, excerpt(para), position=len(self.result.blocks))
                )
                self.pending_ids.extend(ids)
                continue
            self.pending_ids = []
            block = Block(kind, para, level, anchors, notes if i == 0 else [])
            self.result.blocks.append(block)

    def _leaf_text(self, el: Tag) -> str:
        for br in el.find_all("br"):
            br.replace_with("\n")
        return el.get_text()

    def _ids_of(self, el: Tag) -> list[str]:
        ids = [el.attrs["id"]] if el.attrs.get("id") else []
        for sub in el.find_all(True):
            if sub.attrs.get("id"):
                ids.append(sub.attrs["id"])
            if sub.name == "a" and sub.attrs.get("name"):
                ids.append(sub.attrs["name"])
        return ids

    def walk(self, container: Tag) -> None:
        inline: list[object] = []

        def flush() -> None:
            if not inline:
                return
            text = ""
            ids: list[str] = []
            for node in inline:
                if isinstance(node, Tag):
                    ids.extend(self._ids_of(node))
                    text += self._leaf_text(node)
                else:
                    text += str(node)
            inline.clear()
            self._emit("p", 0, text, ids)

        for child in list(container.children):
            if isinstance(child, Tag):
                if child.name in _SKIP_TAGS:
                    continue
                if child.name == "hr":
                    flush()
                    self._emit("p", 0, "* * *", [])
                elif child.name in BLOCK_TAGS:
                    flush()
                    if child.find(BLOCK_TAGS - {"body"}) is not None:
                        if child.attrs.get("id"):
                            self.pending_ids.append(child.attrs["id"])
                        self.walk(child)
                    else:
                        is_h = child.name in ("h1", "h2", "h3", "h4", "h5", "h6")
                        self._emit(
                            "h" if is_h else "p",
                            int(child.name[1]) if is_h else 0,
                            self._leaf_text(child),
                            self._ids_of(child),
                        )
                else:
                    inline.append(child)
            elif isinstance(child, NavigableString):
                inline.append(child)
        flush()


def clean_document(
    path: str, html: bytes, notes: NoteIndex, options: CleanOptions | None = None
) -> DocResult:
    """Nettoie un document HTML de l'ordre de lecture et le découpe en blocs de texte."""
    options = options or CleanOptions()
    soup = _soup(html)
    kind = classify_document(soup, path, options)
    if kind is not None:
        result = DocResult(path, removed_as=kind)
        body_text = (soup.body or soup).get_text(" ")
        result.removals.append(Removal(kind, path, excerpt(body_text), position=0))
        return result
    cleaner = _Cleaner(path, soup, notes, options)
    cleaner.preprocess()
    if soup.body is None:
        return DocResult(path, removed_as=R_EMPTY)
    cleaner.walk(soup.body)
    result = cleaner.result
    if cleaner.soft_hyphens:
        count = cleaner.soft_hyphens
        result.removals.append(Removal(R_SOFT, path, f"{count} caractère(s) supprimé(s)"))
    return result


def drop_running_heads(results: list[DocResult], min_count: int = 5) -> int:
    """Retire les en-têtes et pieds de page répétés (tous documents confondus).

    Les blocs retirés sont consignés ; leurs ancres passent au bloc suivant et les positions des
    retraits sont recalculées. Renvoie le nombre de blocs retirés.
    """
    texts = [b.text for r in results for b in r.blocks if b.kind == "p"]
    keys = running_head_keys(texts, min_count)
    if not keys:
        return 0
    dropped = 0
    for res in results:
        kept: list[Block] = []
        remap: list[int] = []
        carry: list[str] = []
        extra: list[Removal] = []
        for b in res.blocks:
            remap.append(len(kept))
            if b.kind == "p" and running_head_key(b.text) in keys:
                extra.append(Removal(R_HEADER, res.path, excerpt(b.text), position=len(kept)))
                carry.extend(b.anchors)
                dropped += 1
                continue
            if carry:
                b.anchors = tuple(carry) + b.anchors
                carry = []
            kept.append(b)
        remap.append(len(kept))
        for rem in res.removals:
            rem.position = remap[min(rem.position, len(remap) - 1)]
        res.removals.extend(extra)
        res.blocks = kept
    return dropped
