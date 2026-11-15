"""Nombres en lettres (français) et chiffres romains. Fonctions pures.

Orthographe retenue : **traditionnelle** (pas de trait d'union entre les centaines et les
milliers : « mille cinq cent soixante-douze » ; trait d'union seulement entre dizaines et
unités ; « et » dans « vingt et un », « soixante et onze »). Pour une synthèse vocale seule
la prononciation compte, et la forme traditionnelle est celle que les moteurs lisent le mieux.
Accords : « cent » et « vingt » (dans « quatre-vingts ») prennent un s quand rien ne les suit,
sauf devant « mille » ; « million(s) » et « milliard(s) » s'accordent.
"""

from __future__ import annotations

import re

_UNITS = [
    "zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix",
    "onze", "douze", "treize", "quatorze", "quinze", "seize",
]  # fmt: skip
_TENS = {2: "vingt", 3: "trente", 4: "quarante", 5: "cinquante", 6: "soixante"}

MAX_CARDINAL = 10**12 - 1


def _below_100(n: int, final: bool) -> str:
    if n < 17:
        return _UNITS[n]
    if n < 20:
        return "dix-" + _UNITS[n - 10]
    if n < 70:
        tens, unit = divmod(n, 10)
        base = _TENS[tens]
        if unit == 0:
            return base
        if unit == 1:
            return f"{base} et un"
        return f"{base}-{_UNITS[unit]}"
    if n < 80:
        if n == 71:
            return "soixante et onze"
        return "soixante-" + _below_100(n - 60, final)
    if n == 80:
        return "quatre-vingts" if final else "quatre-vingt"
    return "quatre-vingt-" + _below_100(n - 80, final)


def _below_1000(n: int, final: bool) -> str:
    hundreds, rest = divmod(n, 100)
    parts: list[str] = []
    if hundreds == 1:
        parts.append("cent")
    elif hundreds > 1:
        plural = rest == 0 and final
        parts.append(f"{_UNITS[hundreds]} cent{'s' if plural else ''}")
    if rest or not hundreds:
        parts.append(_below_100(rest, final))
    return " ".join(parts)


def cardinal(n: int) -> str:
    """Nombre entier (0 ≤ n < 10¹²) en lettres. Lève ValueError hors de cet intervalle."""
    if not 0 <= n <= MAX_CARDINAL:
        raise ValueError(f"nombre hors intervalle : {n}")
    if n == 0:
        return "zéro"
    parts: list[str] = []
    for value, name in ((10**9, "milliard"), (10**6, "million")):
        q, n = divmod(n, value)
        if q:
            parts.append(f"{_below_1000(q, True)} {name}{'s' if q > 1 else ''}")
    q, n = divmod(n, 1000)
    if q == 1:
        parts.append("mille")
    elif q > 1:
        parts.append(f"{_below_1000(q, False)} mille")
    if n:
        parts.append(_below_1000(n, True))
    return " ".join(parts)


def feminine(words: str) -> str:
    """« un » final → « une » (vingt et un → vingt et une)."""
    if words == "un":
        return "une"
    if words.endswith(" un"):
        return words[:-2] + "une"
    return words


def ordinal(n: int, *, feminine_form: bool = False, plural: bool = False) -> str:
    """Adjectif ordinal : 1 → premier, 5 → cinquième, 21 → vingt et unième."""
    if n == 1:
        word = "première" if feminine_form else "premier"
    else:
        word = cardinal(n)
        if word.endswith(("cents", "vingts", "millions", "milliards")):
            word = word[:-1]
        if word.endswith("cinq"):
            word += "uième"
        elif word.endswith("neuf"):
            word = word[:-1] + "vième"
        elif word.endswith("e"):
            word = word[:-1] + "ième"
        else:
            word += "ième"
    return word + ("s" if plural else "")


def digits_by_digit(digits: str) -> str:
    """Lecture chiffre par chiffre (numéros, zéros initiaux, très grands nombres)."""
    return " ".join(_UNITS[int(c)] for c in digits)


def integer_words(digits: str) -> str:
    """Lecture d'une suite de chiffres : en nombre si possible, sinon chiffre par chiffre."""
    if (len(digits) > 1 and digits[0] == "0") or len(digits) > 12:
        return digits_by_digit(digits)
    return cardinal(int(digits))


def decimal_words(integer: str, fraction: str) -> str:
    """« 3,14 » → « trois virgule quatorze » ; les zéros initiaux de la fraction sont dits."""
    zeros = len(fraction) - len(fraction.lstrip("0"))
    rest = fraction[zeros:]
    frac = " ".join(["zéro"] * zeros + ([integer_words(rest)] if rest else []))
    return f"{integer_words(integer)} virgule {frac}"


# --- chiffres romains -------------------------------------------------------------

ROMAN_PATTERN = r"M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3})"
_ROMAN_RE = re.compile(rf"^{ROMAN_PATTERN}$")
_ROMAN_VALUES = {"M": 1000, "D": 500, "C": 100, "L": 50, "X": 10, "V": 5, "I": 1}


def is_roman(text: str) -> bool:
    """Chiffre romain majuscule valide (1 à 3999)."""
    return bool(text) and _ROMAN_RE.match(text) is not None


def roman_to_int(text: str) -> int:
    if not is_roman(text):
        raise ValueError(f"chiffre romain invalide : {text!r}")
    total = 0
    for i, ch in enumerate(text):
        value = _ROMAN_VALUES[ch]
        if i + 1 < len(text) and _ROMAN_VALUES[text[i + 1]] > value:
            total -= value
        else:
            total += value
    return total
