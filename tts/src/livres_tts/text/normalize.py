"""Normalisation du français écrit pour la synthèse vocale.

`normalize(text, options)` est une fonction **pure** et **idempotente** : le texte produit ne
contient plus rien à convertir (nombres, chiffres romains et abréviations sont en toutes
lettres), donc le normaliser une seconde fois ne change rien (testé).

Conventions de sortie (documentées ici car le lot T2 s'y appuie) :
- une ligne = un paragraphe ; les lignes sont traitées indépendamment ;
- espaces insécables et fines remplacés par des espaces simples ; plus d'espace avant , . ; : ! ?
- guillemets typographiques « texte » (guillemets droits et anglais convertis) ;
- tiret de dialogue : une ligne qui commence par un tiret (-, –, —) commence par « — » ;
  un tiret incise entouré d'espaces devient « — » ;
- points de suspension : trois points ASCII « ... » (lus comme une pause par tous les moteurs) ;
- séparateur de scène (ligne d'astérisques, de tirets bas…) : la ligne « * * * » ;
- sigles : table configurable (`NormalizeOptions.acronyms`), seuls les sigles listés sont épelés.
"""

from __future__ import annotations

import re
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field

from .numbers import (
    ROMAN_PATTERN,
    cardinal,
    decimal_words,
    feminine,
    integer_words,
    ordinal,
    roman_to_int,
)

DEFAULT_ACRONYMS: dict[str, str] = {
    "SNCF": "S N C F", "TGV": "T G V", "RATP": "R A T P", "CNRS": "C N R S", "EDF": "E D F",
    "PDG": "P D G", "CRS": "C R S", "SDF": "S D F", "TVA": "T V A", "URSS": "U R S S",
    "USA": "U S A", "ADN": "A D N", "GPS": "G P S", "CV": "C V", "BD": "B D", "IRM": "I R M",
    "RER": "R E R", "HLM": "H L M", "ONU": "onu", "OTAN": "otan", "UNESCO": "unesco",
    "OVNI": "ovni", "FBI": "F B I", "CIA": "C I A", "KGB": "K G B", "BBC": "B B C",
}  # fmt: skip

DEFAULT_REGNAL_NAMES: tuple[str, ...] = (
    "Jean-Paul", "Henri", "Louis", "François", "Charles", "Philippe", "Jean", "Jules", "Pie",
    "Léon", "Benoît", "Grégoire", "Clément", "Urbain", "Paul", "Alexandre", "Nicolas",
    "Napoléon", "Édouard", "Élisabeth", "Elisabeth", "Catherine", "Richard", "Guillaume",
    "Frédéric", "Ferdinand", "Isabelle", "Innocent", "Boniface", "Sixte", "Alphonse",
    "Emmanuel", "Victor", "Humbert", "Étienne", "Hugues", "Robert", "Lothaire", "Clovis",
    "Childebert", "Pépin", "Othon", "Sigismond", "Ivan", "Pierre", "Georges", "Albert",
    "Léopold", "Baudouin", "Marie", "Charles-Albert", "Victor-Emmanuel", "Jacques", "Thomas",
    "Rodolphe", "Maximilien", "Joseph", "Mathias", "Casimir", "Henri-Philippe",
)  # fmt: skip

# Mots après lesquels un chiffre romain est un numéro (tome III, Chapitre XII…).
CONTEXT_WORDS = (
    "tome|chapitre|livre|partie|acte|scène|volume|section|article|titre|épisode|saison|"
    "annexe|appendice|planche|psaume|tableau|figure|cahier|livraison|numéro"
)

MONTHS = [
    "janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre",
    "octobre", "novembre", "décembre",
]  # fmt: skip


@dataclass
class NormalizeOptions:
    acronyms: Mapping[str, str] = field(default_factory=lambda: dict(DEFAULT_ACRONYMS))
    regnal_names: tuple[str, ...] = DEFAULT_REGNAL_NAMES


_DEFAULT = NormalizeOptions()

_LETTER = "A-Za-zÀ-ÖØ-öø-ÿŒœ"
_UPPER = "A-ZÀ-ÖØ-ÞŒ"
_R = ROMAN_PATTERN
_SPACES_IN_NUMBER = "\u00a0\u202f\u2009 "

_SUPERSCRIPTS = str.maketrans({"ᵉ": "e", "ʳ": "r", "ˢ": "s", "ᵈ": "d", "º": "°"})
_ZERO_WIDTH = dict.fromkeys(map(ord, "­​‌‍﻿⁠"))
_ODD_SPACES = str.maketrans(dict.fromkeys(map(ord, "       "), " "))


def _is_roman_ok(roman: str, *, strict_single: bool = True) -> bool:
    """Un chiffre romain d'une lettre autre que I, V, X est presque toujours autre chose
    (« Le », « De », « Mère », « Ce »…) : on ne le convertit jamais."""
    return not (strict_single and len(roman) == 1 and roman not in "IVX")


# --- chiffres romains en contexte ------------------------------------------------

_ROMAN_SUFFIXED = re.compile(
    rf"(?<![\w'’-])(?=[MDCLXVI])(?P<r>{_R})(?P<s>ers?|ères?|ièmes?|èmes?|es?)(?![\w])"
)
_ROMAN_SIECLE = re.compile(rf"(?<!\w)(?=[MDCLXVI])(?P<r>{_R})(?=\s+siècles?\b)")
_ROMAN_CONTEXT = re.compile(
    rf"(?P<w>\b(?i:{CONTEXT_WORDS})s?\s+)(?P<r>(?=[MDCLXVI]){_R}"
    rf"(?:\s*(?:,|et|à|ou|-|–|—)\s*(?=[MDCLXVI]){_R})*)(?![\w])"
)
_ROMAN_INNER = re.compile(rf"(?<!\w)(?=[MDCLXVI]){_R}(?!\w)")
_ROMAN_LINE = re.compile(rf"^(?P<r>(?=[MDCLXVI]){_R})(?P<p>[.)]?)(?P<rest>\s+\S.*|\s*)$")


def _roman_suffixed(m: re.Match[str]) -> str:
    roman, suffix = m["r"], m["s"]
    if not _is_roman_ok(roman):
        return m[0]
    if suffix.startswith(("er", "ère")) and roman != "I":
        return m[0]  # « Ver », « Vère »… ; « er » n'existe que pour « Ier »
    if suffix in ("e", "es", "ème", "èmes", "ième", "ièmes") and roman == "I":
        return m[0]  # « Ie » n'est pas du français
    n = roman_to_int(roman)
    return ordinal(n, feminine_form=suffix.startswith("ère"), plural=suffix.endswith("s"))


def _roman_context(m: re.Match[str]) -> str:
    def conv(r: re.Match[str]) -> str:
        return cardinal(roman_to_int(r[0])) if _is_roman_ok(r[0]) else r[0]

    return m["w"] + _ROMAN_INNER.sub(conv, m["r"])


def _roman_siecle(m: re.Match[str]) -> str:
    return ordinal(roman_to_int(m["r"])) if _is_roman_ok(m["r"]) else m[0]


def _regnal_pattern(names: tuple[str, ...]) -> re.Pattern[str]:
    alt = "|".join(re.escape(n) for n in sorted(names, key=len, reverse=True))
    return re.compile(
        rf"(?<![\w-])(?P<n>(?i:{alt}))\s+(?P<r>(?=[MDCLXVI]){_R})(?P<s>er|ère)?(?![\w])"
    )


def _roman_regnal(m: re.Match[str]) -> str:
    roman, suffix = m["r"], m["s"]
    if not _is_roman_ok(roman) or (suffix and roman != "I"):
        return m[0]
    n = roman_to_int(roman)
    if n > 30:  # aucun souverain ou pape n'a un numéro d'ordre aussi élevé
        return m[0]
    word = ordinal(1, feminine_form=suffix == "ère") if n == 1 else cardinal(n)
    return f"{m['n']} {word}"


def _normalize_romans(line: str, options: NormalizeOptions) -> str:
    # Chiffre romain seul sur la ligne ou en tête (« XII. Le départ ») : numéro de chapitre.
    stripped = line.strip()
    m = _ROMAN_LINE.match(stripped)
    if m and _is_roman_ok(m["r"]) and roman_to_int(m["r"]) <= 150:  # n° de chapitre plausible
        rest = m["rest"].strip()
        if (not rest and len(stripped) <= 12) or (m["p"] and rest):
            punct = "." if rest else ""
            line = f"{cardinal(roman_to_int(m['r']))}{punct} {rest}".strip()
    line = _ROMAN_SUFFIXED.sub(_roman_suffixed, line)
    line = _ROMAN_SIECLE.sub(_roman_siecle, line)
    line = _ROMAN_CONTEXT.sub(_roman_context, line)
    line = _regnal_pattern(options.regnal_names).sub(_roman_regnal, line)
    return line


# --- abréviations -----------------------------------------------------------------

_ABBREVIATIONS: list[tuple[re.Pattern[str], str | Callable[[re.Match[str]], str]]] = [
    (re.compile(r"(?<![\w.])MM\.?(?=\s)"), "Messieurs"),
    (re.compile(r"(?<![\w.])M\.(?=\s*\S)"), "Monsieur"),
    (re.compile(r"\bMmes\b\.?"), "Mesdames"),
    (re.compile(r"\bMme\b\.?"), "Madame"),
    (re.compile(r"\bMlles\b\.?"), "Mesdemoiselles"),
    (re.compile(r"\bMlle\b\.?"), "Mademoiselle"),
    (re.compile(r"\bMgr\b\.?"), "Monseigneur"),
    (re.compile(rf"\bDr\b\.?(?=\s+[{_UPPER}])"), "Docteur"),
    (re.compile(rf"\bPr\b\.?(?=\s+[{_UPPER}])"), "Professeur"),
    (re.compile(rf"\bSte\b\.?(?=[ -]+[{_UPPER}])"), "Sainte"),
    (re.compile(rf"\bSt\b\.?(?=[ -]+[{_UPPER}])"), "Saint"),
    (re.compile(r"\bCie\b"), "compagnie"),
    (re.compile(r"\bav(?:ant|\.)\s*J\.?-?\s?C\.?"), "avant Jésus-Christ"),
    (re.compile(r"\bapr(?:ès|\.)\s*J\.?-?\s?C\.?"), "après Jésus-Christ"),
    (re.compile(r"\bJ\.-?\s?C\.?"), "Jésus-Christ"),
    (re.compile(r"\bp\.\s?ex\.?"), "par exemple"),
    (re.compile(r"\bc\.-à-d\.?"), "c'est-à-dire"),
    (re.compile(r"\bcf\.\s*"), "voir "),
    (
        re.compile(rf"\betc\.(?P<end>(?=\s*$|\s+[«\"—–{_UPPER}]))?"),
        lambda m: "et cetera." if m["end"] is not None else "et cetera",
    ),
    (re.compile(r"\b[Nn][°]\s*"), "numéro "),
    (re.compile(r"§\s*"), "paragraphe "),
    (re.compile(r"\s*&\s*"), " et "),
    (re.compile(r"½"), "un demi"),
    (re.compile(r"¼"), "un quart"),
    (re.compile(r"¾"), "trois quarts"),
]


# --- nombres, dates, heures, unités -----------------------------------------------

_DATE_NUM = re.compile(r"(?<![\w/.])(\d{1,2})[/.](\d{1,2})[/.](\d{4})(?![\w/])")
_ORDINAL_DIGITS = re.compile(
    r"(?<![\w,.])(\d+)(?P<s>ers|ère|ères|er|re|res|ème|èmes|nds|ndes|nd|nde|de|es|e)(?![\w])"
)
_TIME = re.compile(r"(?<![\w.,])(\d{1,2})\s?h(?:\s?(\d{2}))?(?![\w])")
_UNIT = re.compile(r"(?<![\w.,])(?P<n>\d+(?:,\d+)?)\s?(?P<u>km|kg|cm|mm|m|g|€|°C)(?![\w'’])")
_DECIMAL = re.compile(r"(?<![\w.,])(\d+),(\d+)(?![\w])")
_INTEGER = re.compile(r"(?<![\w.,])\d+(?![\w])")
_YEAR_RANGE = re.compile(r"(?<![\w.,])(\d{4})\s?[-–—]\s?(\d{4})(?![\w])")
_PERCENT = re.compile(r"\s?%")
_GROUPED = re.compile(rf"(?<![\w.,])\d{{1,3}}(?:[{_SPACES_IN_NUMBER}]\d{{3}})+(?![\w])")

_UNITS_FR = {
    "km": ("kilomètre", "kilomètres"), "kg": ("kilogramme", "kilogrammes"),
    "cm": ("centimètre", "centimètres"), "mm": ("millimètre", "millimètres"),
    "m": ("mètre", "mètres"), "g": ("gramme", "grammes"), "€": ("euro", "euros"),
    "°C": ("degré Celsius", "degrés Celsius"),
}  # fmt: skip


def _is_singular(num: str) -> bool:
    return float(num.replace(",", ".")) < 2


def _date_num(m: re.Match[str]) -> str:
    day, month, year = int(m[1]), int(m[2]), m[3]
    if not (1 <= day <= 31 and 1 <= month <= 12):
        return m[0]
    day_words = "premier" if day == 1 else cardinal(day)
    return f"{day_words} {MONTHS[month - 1]} {integer_words(year)}"


def _ordinal_digits(m: re.Match[str]) -> str:
    n, suffix = int(m[1]), m["s"]
    plural = suffix.endswith("s")
    if suffix.startswith("nd") or suffix in ("de",):
        word = "seconde" if suffix.startswith(("nde", "de")) else "second"
        if n != 2:
            return m[0]
        return word + ("s" if plural else "")
    if suffix.startswith(("er", "re", "ère")):
        if n != 1:
            return m[0]
        return ordinal(1, feminine_form=suffix.startswith(("re", "ère")), plural=plural)
    if len(m[1]) > 12:
        return m[0]
    if n == 1:  # « 1e » : forme fautive courante pour « 1re »
        return ordinal(1, feminine_form=True, plural=plural)
    return ordinal(n, plural=plural)


def _time(m: re.Match[str]) -> str:
    hours = int(m[1])
    if hours > 24:
        return m[0]
    out = f"{feminine(cardinal(hours))} {'heure' if hours < 2 else 'heures'}"
    if m[2]:
        minutes = int(m[2])
        if minutes > 59:
            return m[0]
        out += " " + (cardinal(minutes) if m[2][0] != "0" else _minutes_words(m[2]))
    return out


def _minutes_words(two: str) -> str:
    return "zéro " + cardinal(int(two[1])) if two != "00" else ""


def _unit(m: re.Match[str]) -> str:
    num, unit = m["n"], m["u"]
    singular, plural = _UNITS_FR[unit]
    words = decimal_words(*num.split(",")) if "," in num else integer_words(num)
    return f"{words} {singular if _is_singular(num) else plural}"


def _year_range(m: re.Match[str]) -> str:
    return f"{integer_words(m[1])} à {integer_words(m[2])}"


def _normalize_numbers(line: str) -> str:
    line = _GROUPED.sub(lambda m: re.sub(f"[{_SPACES_IN_NUMBER}]", "", m[0]), line)
    line = _DATE_NUM.sub(_date_num, line)
    line = _ORDINAL_DIGITS.sub(_ordinal_digits, line)
    line = _TIME.sub(_time, line)
    line = _PERCENT.sub(" pour cent", line)
    line = _UNIT.sub(_unit, line)
    line = _YEAR_RANGE.sub(_year_range, line)
    line = _DECIMAL.sub(lambda m: decimal_words(m[1], m[2]), line)
    return _INTEGER.sub(lambda m: integer_words(m[0]), line)


# --- ponctuation, guillemets, tirets -----------------------------------------------

_SCENE_BREAK = re.compile(r"^[\s*•·#~=_\-–—]+$")
_DASHES = "-–—―‒"


def _pair_straight_quotes(text: str) -> str:
    if text.count('"') % 2:
        return text  # nombre impair : ambigu, on ne touche pas
    out: list[str] = []
    opening = True
    for ch in text:
        if ch == '"':
            out.append("«" if opening else "»")
            opening = not opening
        else:
            out.append(ch)
    return "".join(out)


def _normalize_punctuation(line: str) -> str:
    line = line.replace("“", "«").replace("”", "»").replace("„", "«").replace("‟", "«")
    line = line.replace("‘", "'").replace("’", "'").replace("ʼ", "'")
    line = _pair_straight_quotes(line)
    line = re.sub(r"«\s*", "« ", line)
    line = re.sub(r"\s*»", " »", line)
    line = re.sub(r"\.{3,}|…+", "...", line)
    line = re.sub(rf"(?<=\s)[{_DASHES}](?=\s)", "—", line)
    line = re.sub(r"\s+([,.;:!?])", r"\1", line)
    line = re.sub(rf"(?<=[,;!?])(?=[{_LETTER}«])", " ", line)
    line = re.sub(r"[ \t]+", " ", line)
    return line.strip()


def _normalize_line(line: str, options: NormalizeOptions) -> str:
    line = line.translate(_ZERO_WIDTH).translate(_SUPERSCRIPTS)
    # Les séparateurs de milliers insécables sont traités avant l'aplatissement des espaces.
    line = _GROUPED.sub(lambda m: re.sub(f"[{_SPACES_IN_NUMBER}]", "", m[0]), line)
    line = line.translate(_ODD_SPACES).replace("\t", " ").strip()
    if not line:
        return ""
    if _SCENE_BREAK.match(line) and (line.count("*") or len(line.replace(" ", "")) >= 3):
        return "* * *"
    dialogue = re.match(rf"^[{_DASHES}]\s*(?=\S)", line)
    if dialogue:
        line = "— " + line[dialogue.end() :]
    for pattern, repl in _ABBREVIATIONS:
        line = pattern.sub(repl, line)
    line = _normalize_romans(line, options)
    line = _normalize_numbers(line)
    acr = options.acronyms
    if acr:
        line = re.sub(r"(?<!\w)[A-Z]{2,6}(?![\w])", lambda m: acr.get(m[0], m[0]), line)
    return _normalize_punctuation(line)


# Apostrophe de sortie : typographique (U+2019). Google Chirp 3 HD épelle la lettre isolée
# devant une apostrophe droite (« n'a » → « N … a ») ; elle lit correctement « n’a »
# (écoute de l'utilisateur, 2026-11-15). Le traitement interne reste fait avec l'apostrophe droite.
OUTPUT_APOSTROPHE = "\u2019"
_ELISION = re.compile(rf"(?<=[{_LETTER}])'(?=[{_LETTER}])")


def normalize(text: str, options: NormalizeOptions | None = None) -> str:
    """Normalise un texte (une ou plusieurs lignes). Idempotente."""
    opts = options or _DEFAULT
    lines = (_normalize_line(line, opts) for line in text.replace("\r", "").split("\n"))
    return "\n".join(_ELISION.sub(OUTPUT_APOSTROPHE, line) for line in lines)
