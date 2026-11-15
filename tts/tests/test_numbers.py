import pytest

from livres_tts.text.numbers import (
    cardinal,
    decimal_words,
    feminine,
    integer_words,
    is_roman,
    ordinal,
    roman_to_int,
)

CARDINALS = [
    (0, "zéro"), (1, "un"), (2, "deux"), (9, "neuf"), (10, "dix"), (11, "onze"),
    (16, "seize"), (17, "dix-sept"), (19, "dix-neuf"), (20, "vingt"), (21, "vingt et un"),
    (22, "vingt-deux"), (30, "trente"), (31, "trente et un"), (45, "quarante-cinq"),
    (60, "soixante"), (61, "soixante et un"), (70, "soixante-dix"), (71, "soixante et onze"),
    (72, "soixante-douze"), (77, "soixante-dix-sept"), (80, "quatre-vingts"),
    (81, "quatre-vingt-un"), (90, "quatre-vingt-dix"), (91, "quatre-vingt-onze"),
    (99, "quatre-vingt-dix-neuf"), (100, "cent"), (101, "cent un"), (200, "deux cents"),
    (201, "deux cent un"), (280, "deux cent quatre-vingts"), (999, "neuf cent quatre-vingt-dix-neuf"),
    (1000, "mille"), (1001, "mille un"), (1572, "mille cinq cent soixante-douze"),
    (1789, "mille sept cent quatre-vingt-neuf"), (1980, "mille neuf cent quatre-vingts"),
    (2000, "deux mille"), (2024, "deux mille vingt-quatre"), (21000, "vingt et un mille"),
    (80000, "quatre-vingt mille"), (200000, "deux cent mille"),
    (1_000_000, "un million"), (2_000_000, "deux millions"),
    (1_200_000, "un million deux cent mille"), (200_000_000, "deux cents millions"),
    (1_000_000_000, "un milliard"), (3_000_001, "trois millions un"),
]  # fmt: skip

ORDINALS = [
    (1, "premier"), (2, "deuxième"), (3, "troisième"), (4, "quatrième"), (5, "cinquième"),
    (9, "neuvième"), (11, "onzième"), (16, "seizième"), (20, "vingtième"),
    (21, "vingt et unième"), (80, "quatre-vingtième"), (100, "centième"), (200, "deux centième"),
    (1000, "millième"), (19, "dix-neuvième"), (6, "sixième"),
]  # fmt: skip


@pytest.mark.parametrize(("n", "words"), CARDINALS)
def test_cardinal(n, words):
    assert cardinal(n) == words


@pytest.mark.parametrize(("n", "words"), ORDINALS)
def test_ordinal(n, words):
    assert ordinal(n) == words


def test_ordinal_forms():
    assert ordinal(1, feminine_form=True) == "première"
    assert ordinal(1, plural=True) == "premiers"
    assert ordinal(3, plural=True) == "troisièmes"


def test_cardinal_out_of_range():
    with pytest.raises(ValueError):
        cardinal(-1)
    with pytest.raises(ValueError):
        cardinal(10**12)


def test_feminine():
    assert feminine("un") == "une"
    assert feminine("vingt et un") == "vingt et une"
    assert feminine("douze") == "douze"


def test_integer_and_decimal_words():
    assert integer_words("007") == "zéro zéro sept"
    assert (
        integer_words("1234567890123")
        == "un deux trois quatre cinq six sept huit neuf zéro un deux trois"
    )
    assert decimal_words("3", "14") == "trois virgule quatorze"
    assert decimal_words("0", "05") == "zéro virgule zéro cinq"
    assert decimal_words("2", "50") == "deux virgule cinquante"


@pytest.mark.parametrize(
    ("roman", "value"),
    [("I", 1), ("IV", 4), ("IX", 9), ("XIV", 14), ("XVI", 16), ("XLII", 42), ("XC", 90),
     ("CDXLIV", 444), ("MCMXCIX", 1999), ("MMXXIV", 2024), ("MMMCMXCIX", 3999)],
)  # fmt: skip
def test_roman_to_int(roman, value):
    assert roman_to_int(roman) == value


@pytest.mark.parametrize("bad", ["", "IIII", "VX", "IC", "ABC", "iv", "MMMM", "Vi"])
def test_invalid_roman(bad):
    assert not is_roman(bad)
    with pytest.raises(ValueError):
        roman_to_int(bad)
