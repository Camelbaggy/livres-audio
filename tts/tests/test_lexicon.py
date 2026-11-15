from __future__ import annotations

import pytest

from livres_tts.lexicon import Lexicon, LexiconError, load_lexicon, parse_entries


def lex(*layers):
    return Lexicon.from_layers(*[parse_entries(layer) for layer in layers])


@pytest.mark.parametrize(
    ("entries", "text", "expected"),
    [
        ([{"mot": "Siorac", "dire": "Sioraque"}], "Siorac et Siorac.", "Sioraque et Sioraque."),
        ([{"mot": "Siorac", "dire": "X"}], "Sioracs et siorac", "Sioracs et siorac"),  # limites, casse
        ([{"mot": "siorac", "dire": "X", "casse": False}], "Siorac, SIORAC", "X, X"),
        ([{"mot": "Mespech", "dire": "A"}], "Mespech-le-Haut", "A-le-Haut"),
        (
            [{"mot": "Mespech", "dire": "A"}, {"mot": "Mespech-le-Haut", "dire": "B"}],
            "Mespech-le-Haut puis Mespech",
            "B puis A",
        ),  # la plus longue gagne à la même position
        ([{"regex": r"Maître (\w+)", "dire": r"Maitre \1"}], "Maître Jean", "Maitre Jean"),
        ([{"mot": "a", "dire": "b"}, {"mot": "b", "dire": "c"}], "a b", "b c"),  # pas de cascade
        ([{"regex": "x*", "dire": "!"}], "abc", "abc"),  # correspondances vides ignorées
    ],
)
def test_apply(entries, text, expected):
    assert lex(entries).apply(text) == expected


def test_series_overrides_global_and_wins_ties():
    g = [{"mot": "Siorac", "dire": "global"}, {"mot": "Mespech", "dire": "g2"}]
    s = [{"mot": "Siorac", "dire": "serie"}]
    assert lex(g, s).apply("Siorac Mespech") == "serie g2"
    # deux clés différentes sur le même mot : la série passe avant le global
    g2 = [{"mot": "Siorac", "dire": "global", "casse": False}]
    assert lex(g2, s).apply("Siorac") == "serie"


def test_deterministic_and_idempotent_input_order():
    entries = [{"mot": "ab", "dire": "1"}, {"mot": "abc", "dire": "2"}]
    assert lex(entries).apply("abc ab") == lex(list(reversed(entries))).apply("abc ab") == "2 1"


@pytest.mark.parametrize(
    "bad",
    [
        {"mot": "a"},
        {"dire": "a"},
        {"mot": "a", "regex": "b", "dire": "c"},
        {"mot": "a", "dire": "b", "casse": "non"},
        {"regex": "(", "dire": "b"},
        "texte",
    ],
)
def test_invalid_entries(bad):
    with pytest.raises(LexiconError):
        parse_entries([bad])


def test_load_lexicon_files(tmp_path):
    (tmp_path / "global.yaml").write_text("- mot: Un\n  dire: Deux\n", "utf-8")
    (tmp_path / "ma-serie.yaml").write_text("- mot: Trois\n  dire: Quatre\n", "utf-8")
    lx = load_lexicon("Ma Série", tmp_path)
    assert lx.apply("Un Trois") == "Deux Quatre"
    assert load_lexicon(None, tmp_path).apply("Un Trois") == "Deux Trois"
    with pytest.raises(LexiconError):
        load_lexicon("inconnue", tmp_path)
    (tmp_path / "global.yaml").write_text("- mot: [", "utf-8")
    with pytest.raises(LexiconError):
        load_lexicon(None, tmp_path)


def test_shipped_lexicons_parse():
    from livres_tts.lexicon import default_lexicon_dir

    lx = load_lexicon("fortune-de-france", default_lexicon_dir())
    assert lx.apply("Maître Pierre") == "Maitre Pierre"
