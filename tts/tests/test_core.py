from __future__ import annotations

import json
import re
import shutil
import wave

import pytest

from livres_tts import bench as bench_mod
from livres_tts.cli import main
from livres_tts.engines import Engine, EngineUnavailable, engine_names, get_engine
from livres_tts.page import render_page
from livres_tts.textsplit import chunk_text

needs_ffmpeg = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg absent")


class FakeEngine(Engine):
    name = "fake"
    max_chars = 40
    default_voice = "v0"

    def __init__(self, name: str = "fake", paid: bool = False, available: bool = True):
        self.name = name
        self.is_paid = paid
        self._available = available
        self.calls: list[str] = []

    def check_available(self) -> None:
        if not self._available:
            raise EngineUnavailable(f"{self.name} : absent")

    def synthesize(self, text, voice=None, params=None, out_path=None):
        self.calls.append(text)
        dst = self._out(out_path)
        with wave.open(str(dst), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(24000)
            w.writeframes(b"\x00\x10" * 2400)
        return dst


def factory(engines: dict[str, FakeEngine]):
    return lambda name: engines[name]


def test_registry_names_and_lazy_import():
    names = engine_names()
    for n in ("edge_tts", "piper", "kokoro", "openai"):
        assert n in names
    assert get_engine("piper").name == "piper"
    assert get_engine("openai").is_paid
    with pytest.raises(KeyError):
        get_engine("inconnu")


def test_missing_dependency_is_reported_not_crashing():
    eng = get_engine("kokoro")
    try:
        eng.check_available()
    except EngineUnavailable as exc:
        assert "kokoro" in str(exc)


def test_chunking():
    text = "Une phrase. Une autre phrase ici ! Et enfin la dernière phrase du texte ?"
    chunks = chunk_text(text, 30)
    assert all(len(c) <= 30 for c in chunks)
    assert " ".join(chunks) == text
    assert chunk_text(text, None) == [text]
    assert chunk_text("  ", 10) == []
    long = "mot " * 30
    assert all(len(c) <= 20 for c in chunk_text(long.strip(), 20))


def test_page_generation_is_standalone():
    html = render_page("id1", ["voix-A.m4a", "voix-B.m4a"], "Texte <b>x</b> </script>")
    assert "voix-A.m4a" in html and "localStorage" in html and "Blob" in html
    assert "<b>x</b>" not in html  # échappé
    assert "http://" not in html and "https://" not in html
    for crit in ("Naturel", "Rythme", "dialogues", "Noms propres", "Fatigue"):
        assert crit in html


def test_paid_refused_without_flag(tmp_path):
    engines = {"p": FakeEngine("p", paid=True)}
    with pytest.raises(bench_mod.PaidEngineRefused):
        bench_mod.run_bench("Bonjour.", ["p"], tmp_path, engine_factory=factory(engines))
    assert engines["p"].calls == []


def test_parse_voices():
    assert bench_mod.parse_voices("a=x,b=y z") == {"a": "x", "b": "y z"}
    with pytest.raises(ValueError):
        bench_mod.parse_voices("a")


def test_cli_refuses_paid(tmp_path, capsys):
    (tmp_path / "x.txt").write_text("Bonjour.", "utf-8")
    code = main(
        ["bench", str(tmp_path / "x.txt"), "--moteurs", "openai", "--sortie", str(tmp_path / "o")]
    )
    assert code == 2
    assert "--autoriser-payant" in capsys.readouterr().err


def test_cli_pending_command(capsys):
    assert main(["build"]) == 3
    assert "pas encore disponible (lot T2)" in capsys.readouterr().err


@needs_ffmpeg
def test_bench_anonymises_and_writes_key(tmp_path):
    engines = {
        "a": FakeEngine("a"),
        "b": FakeEngine("b"),
        "c": FakeEngine("c"),
        "off": FakeEngine("off", available=False),
    }
    text = "Première phrase assez longue. Deuxième phrase tout aussi longue. Troisième."
    res = bench_mod.run_bench(
        text,
        ["a", "b", "c", "off"],
        tmp_path,
        voices={"a": "vx"},
        seed=1,
        engine_factory=factory(engines),
    )
    files = sorted(p.name for p in tmp_path.glob("*.m4a"))
    assert files == ["voix-A.m4a", "voix-B.m4a", "voix-C.m4a"]
    assert len(engines["a"].calls) > 1  # découpé selon max_chars
    key = json.loads((tmp_path / "cle.json").read_text("utf-8"))
    assert {v["moteur"] for v in key["voix"].values()} == {"a", "b", "c"}
    assert "off:v0" in key["ignores"] and "off:v0" in res.skipped
    page = (tmp_path / "ecoute.html").read_text("utf-8")
    assert "voix-A.m4a" in page
    # la page ne révèle pas les moteurs
    assert not re.search(r"\b(fake|engine)\b", page.split("<script>")[0])
    for name in ("a", "b", "c"):
        assert f">{name}<" not in page
    assert next(v for v in key["voix"].values() if v["moteur"] == "a")["voix"] == "vx"


@needs_ffmpeg
def test_bench_several_voices_of_one_engine(tmp_path):
    engines = {"a": FakeEngine("a")}
    bench_mod.run_bench(
        "Une phrase.",
        ["a:v1", "a:v2", "a"],
        tmp_path,
        seed=2,
        engine_factory=factory(engines),
    )
    key = json.loads((tmp_path / "cle.json").read_text("utf-8"))
    assert sorted(v["voix"] for v in key["voix"].values()) == ["v0", "v1", "v2"]
    assert len(list(tmp_path.glob("voix-*.m4a"))) == 3
