"""build / fix / estimate / say avec un faux moteur payant (jamais d'appel réseau)."""

from __future__ import annotations

import json
import math
import shutil
import struct
import subprocess
import wave
from pathlib import Path

import pytest

from livres_tts.build import BuildRefused, RunOptions, estimate, run, say, voice_label
from livres_tts.cli import main
from livres_tts.engines import Engine, EngineUnavailable
from livres_tts.ledger import Ledger
from livres_tts.project import BuildSettings, load_project

pytestmark = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg absent")

CH1 = [
    "Siorac entra. Il parla. Puis il sortit.",
    "— Où allez-vous, Siorac ? Dites-le.",
    "* * *",
    "Le lendemain, Mespech dormait encore.",
]
CH2 = ["Un autre chapitre commence ici. Il est court.", "— Oui, dit-il."]


class PaidFake(Engine):
    name = "paid"
    is_paid = True
    price_per_million_chars = 20.0
    free_chars_per_month = 100_000
    max_chars = 200
    default_voice = "fr-FR-Chirp3-HD-Aoede"

    def __init__(self, ledger: Ledger | None = None, fail_on_call: int | None = None):
        self.calls: list[str] = []
        self.ledger = ledger
        self.used_at_call: list[int] = []
        self.fail_on_call = fail_on_call

    def synthesize(self, text, voice=None, params=None, out_path=None):
        if self.ledger is not None:
            # la réservation doit déjà être écrite quand l'appel part
            self.used_at_call.append(self.ledger.used(self.name))
        self.calls.append(text)
        if self.fail_on_call is not None and len(self.calls) == self.fail_on_call:
            raise EngineUnavailable("paid : panne simulée")
        dst = self._out(out_path)
        n = int(24000 * (0.02 * len(text) + 0.1))
        with wave.open(str(dst), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(24000)
            w.writeframes(
                b"".join(
                    struct.pack("<h", int(8000 * math.sin(i / 20)))
                    for i in range(0, n, 1)
                )
            )
        return dst


class FreeFake(PaidFake):
    name = "free"
    is_paid = False
    price_per_million_chars = 0.0
    free_chars_per_month = 0


def make_project(root: Path, chapters=(CH1, CH2), with_cover=True) -> Path:
    proj = root / "projet"
    (proj / "chapitres").mkdir(parents=True)
    entries = []
    for i, lines in enumerate(chapters, 1):
        name = f"{i:02d} - Chapitre {i}.txt"
        (proj / "chapitres" / name).write_text("\n".join(lines) + "\n", "utf-8")
        entries.append(
            {
                "index": i,
                "titre": f"Chapitre {i}",
                "titre_lu": f"Chapitre {i}.",
                "fichier": f"chapitres/{name}",
            }
        )
    cover = None
    if with_cover:
        cover = "couverture.jpg"
        subprocess.run(
            ["ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=s=200x300:d=1",
             "-frames:v", "1", str(proj / cover)],
            check=True,
        )  # fmt: skip
    (proj / "projet.json").write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "livre": {
                    "titre": "Livre d'essai",
                    "auteurs": ["Jeanne Auteure"],
                    "annee": 1985,
                    "serie": "Série d'essai",
                    "tome": "2",
                },
                "couverture": cover,
                "chapitres": entries,
            }
        ),
        "utf-8",
    )
    return proj


@pytest.fixture
def lexdir(tmp_path):
    d = tmp_path / "lex"
    d.mkdir()
    (d / "global.yaml").write_text("[]\n", "utf-8")
    (d / "serie.yaml").write_text("- mot: Mespech\n  dire: Mespèque\n", "utf-8")
    return d


@pytest.fixture
def env(tmp_path, lexdir):
    state = {"m": "2026-11"}
    ledger = Ledger(tmp_path / "cfg" / "registre.json", month=lambda: state["m"])
    project = load_project(make_project(tmp_path))
    settings = BuildSettings(
        moteur="paid", voix="fr-FR-Chirp3-HD-Aoede", serie="serie", lexiques=str(lexdir)
    )
    return project, settings, ledger, state


def go(project, settings, engine, ledger, fix=False, **opts):
    opts.setdefault("oui", True)
    return run(
        project, settings, engine, RunOptions(**opts), ledger=ledger, log=lambda _m: None, fix=fix
    )


def total_chars(env_):
    project, settings, ledger, _ = env_
    from livres_tts.lexicon import load_lexicon
    from livres_tts.synth import make_plan

    e = PaidFake()
    return make_plan(
        project, e, settings.voix, {}, load_lexicon("serie", Path(settings.lexiques)), settings.silences
    ).total_chars


def test_full_build_outputs_and_tags(env):
    project, settings, ledger, _ = env
    eng = PaidFake(ledger)
    rep = go(project, settings, eng, ledger)
    assert not rep.partial and len(rep.assembled) == 2
    # lexique appliqué avant la synthèse
    assert any("Mespèque" in t for t in eng.calls) and not any("Mespech" in t for t in eng.calls)
    # réservation écrite avant chaque appel : usage ≥ caractères de l'appel en cours
    assert all(u >= len(t) for u, t in zip(eng.used_at_call, eng.calls, strict=True))
    assert ledger.used("paid") == sum(len(t) for t in eng.calls)
    out = project.out_dir / "01 - Chapitre 1.m4a"
    info = json.loads(
        subprocess.run(
            ["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(out)],
            capture_output=True, text=True, check=True,
        ).stdout
    )  # fmt: skip
    audio = next(s for s in info["streams"] if s["codec_type"] == "audio")
    assert audio["codec_name"] == "aac" and audio["channels"] == 1
    assert 10_000 < int(audio["bit_rate"]) < 75_000  # AAC, plafond nominal 64 kb/s
    assert any(s["codec_type"] == "video" for s in info["streams"])  # couverture
    tags = info["format"]["tags"]
    assert tags["title"] == "Chapitre 1" and tags["album"] == "Livre d'essai"
    assert tags["artist"] == "Jeanne Auteure" and tags["track"] == "1/2"
    assert tags["composer"] == "Voix de synthèse (Aoede)" and tags["date"] == "1985"
    assert tags["grouping"] == "Série d'essai" and "tome 2" in tags["comment"]


def test_resume_uses_cache_and_nothing_to_do(env):
    project, settings, ledger, _ = env
    first = PaidFake(ledger)
    go(project, settings, first, ledger)
    used = ledger.used("paid")
    second = PaidFake(ledger)
    rep = go(project, settings, second, ledger)
    assert rep.nothing and second.calls == [] and ledger.used("paid") == used


def test_interruption_then_resume_only_missing(env):
    project, settings, ledger, _ = env
    crash = PaidFake(ledger, fail_on_call=3)
    rep = go(project, settings, crash, ledger)
    assert rep.synth.error and rep.partial
    done = len(crash.calls) - 1
    # l'appel en échec reste compté (prudence) ; les précédents sont confirmés
    assert ledger.used("paid") == sum(len(t) for t in crash.calls)
    again = PaidFake(ledger)
    rep = go(project, settings, again, ledger)
    assert not rep.partial and len(rep.assembled) == 2
    assert len(again.calls) + done == len(set(crash.calls[:done] + again.calls))  # pas de refait


def test_refused_before_any_call_when_free_tier_insufficient(env):
    project, settings, ledger, _ = env
    eng = PaidFake(ledger)
    eng.free_chars_per_month = 50
    with pytest.raises(BuildRefused) as exc:
        go(project, settings, eng, ledger)
    assert "--budget-max" in str(exc.value) and "€" in str(exc.value)
    assert eng.calls == [] and not ledger.path.exists()


def test_stop_net_at_free_tier_then_resume_next_month(env):
    project, settings, ledger, state = env
    eng = PaidFake(ledger)
    eng.free_chars_per_month = 90
    rep = go(project, settings, eng, ledger, jusqu_au_palier=True)
    assert rep.synth.stopped and rep.partial
    assert ledger.used("paid") <= 90 and 0 < len(eng.calls)
    # même mois : rien de plus n'est envoyé
    n = len(eng.calls)
    rep2 = go(project, settings, eng, ledger, jusqu_au_palier=True)
    assert len(eng.calls) == n and rep2.partial or rep2.limits.free_left < 50
    # mois suivant : le palier est neuf, la reprise continue sans refaire
    state["m"] = "2026-12"
    for _ in range(10):
        before = len(eng.calls)
        rep3 = go(project, settings, eng, ledger, jusqu_au_palier=True)
        if not rep3.partial:
            break
        state["m"] = f"2027-{len(eng.calls):02d}"  # encore un autre mois
        assert len(eng.calls) > before
    assert not rep3.partial
    texts = eng.calls
    assert len(texts) == len(set(texts))  # aucun segment synthétisé deux fois


def test_budget_max_allows_overflow_and_stops_at_cap(env):
    project, settings, ledger, _ = env
    eng = PaidFake(ledger)
    eng.free_chars_per_month = 50
    # 20 €/M → 0,001 € = 50 caractères au-delà du palier ; plafond total ≈ 100
    rep = go(project, settings, eng, ledger, budget_max=0.001)
    assert rep.synth.stopped and ledger.used("paid") <= 100
    eng2 = PaidFake(ledger)
    eng2.free_chars_per_month = 50
    rep = go(project, settings, eng2, ledger, budget_max=1.0)  # plafond large : tout passe
    assert not rep.partial and len(rep.assembled) == 2


def test_budget_cap_is_per_run_after_previous_overflow(env):
    project, settings, ledger, _ = env
    ledger.reserve("paid", 500, 10**9)  # déjà 500 > palier de 50
    eng = PaidFake(ledger)
    eng.free_chars_per_month = 50
    go(project, settings, eng, ledger, budget_max=0.001)
    assert 500 < ledger.used("paid") <= 550


def test_max_caracteres_limits_run(env):
    project, settings, ledger, _ = env
    eng = PaidFake(ledger)
    rep = go(project, settings, eng, ledger, max_caracteres=60)
    assert rep.synth.stopped and ledger.used("paid") <= 60
    assert rep.limits.run_chars == 60


def test_corrupted_ledger_refuses_all_calls(env):
    project, settings, ledger, _ = env
    ledger.path.parent.mkdir(parents=True)
    ledger.path.write_text("{ceci n'est pas du json", "utf-8")
    eng = PaidFake(ledger)
    with pytest.raises(BuildRefused):
        go(project, settings, eng, ledger)
    assert eng.calls == []
    assert ledger.path.read_text("utf-8") == "{ceci n'est pas du json"


def test_confirmation_declined_means_no_call(env):
    project, settings, ledger, _ = env
    eng = PaidFake(ledger)
    rep = run(
        project, settings, eng, RunOptions(), ledger=ledger, log=lambda _m: None,
        confirm=lambda _p: False,
    )  # fmt: skip
    assert rep.aborted and eng.calls == [] and not project.out_dir.exists()


def test_confirmation_accepted_and_free_engine_needs_none(env):
    project, settings, ledger, _ = env
    asked = []
    eng = PaidFake(ledger)
    run(project, settings, eng, RunOptions(), ledger=ledger, log=lambda _m: None,
        confirm=lambda p: asked.append(p) or True)  # fmt: skip
    assert len(asked) == 1 and eng.calls
    free_settings = BuildSettings(moteur="free", voix="v", serie="serie", lexiques=settings.lexiques)
    free = FreeFake()
    p2 = load_project(make_project(project.dir.parent / "autre"))
    run(p2, free_settings, free, RunOptions(), ledger=ledger, log=lambda _m: None,
        confirm=lambda _p: pytest.fail("confirmation inutile"))  # fmt: skip
    assert free.calls and ledger.used("free") == 0


def test_fix_resynthesizes_only_changed_segments_and_chapters(env, lexdir):
    project, settings, ledger, _ = env
    go(project, settings, PaidFake(ledger), ledger)
    out1 = project.out_dir / "01 - Chapitre 1.m4a"
    out2 = project.out_dir / "02 - Chapitre 2.m4a"
    m1, m2 = out1.stat().st_mtime_ns, out2.stat().st_mtime_ns
    used = ledger.used("paid")
    # correction du lexique : « Siorac » ne figure que dans le chapitre 1
    (lexdir / "serie.yaml").write_text(
        "- mot: Mespech\n  dire: Mespèque\n- mot: Siorac\n  dire: Sioraque\n", "utf-8"
    )
    eng = PaidFake(ledger)
    rep = go(project, settings, eng, ledger, fix=True)
    changed = [t for t in eng.calls]
    assert changed and all("Sioraque" in t for t in changed)
    assert len(changed) < 5
    assert ledger.used("paid") == used + sum(len(t) for t in changed)
    assert out1.stat().st_mtime_ns != m1 and out2.stat().st_mtime_ns == m2
    assert [p.name for p in rep.assembled] == ["01 - Chapitre 1.m4a"]


def test_fix_without_change_does_nothing(env):
    project, settings, ledger, _ = env
    go(project, settings, PaidFake(ledger), ledger)
    eng = PaidFake(ledger)
    rep = go(project, settings, eng, ledger, fix=True)
    assert rep.nothing and eng.calls == []


def test_silence_change_reassembles_without_synthesis(env):
    project, settings, ledger, _ = env
    go(project, settings, PaidFake(ledger), ledger)
    settings.silences.paragraphe = 0.9
    eng = PaidFake(ledger)
    rep = go(project, settings, eng, ledger, fix=True)
    assert eng.calls == [] and len(rep.assembled) == 2


def test_cache_key_depends_on_voice_and_engine(env):
    project, settings, ledger, _ = env
    go(project, settings, PaidFake(ledger), ledger)
    other = BuildSettings(moteur="paid", voix="autre-voix", serie="serie", lexiques=settings.lexiques)
    eng = PaidFake(ledger)
    go(project, other, eng, ledger)
    assert eng.calls  # autre voix = autres clés = tout est à refaire


def test_estimate_reports_free_tier(env):
    project, settings, ledger, _ = env
    ledger.reserve("paid", 1000, 10**9)
    lines = "\n".join(estimate(project, settings, PaidFake(ledger), ledger))
    assert "99 000 restants" in lines and "100 000" in lines
    assert "0.00 €" in lines
    ledger.path.write_text("cassé", "utf-8")
    assert "inutilisable" in "\n".join(estimate(project, settings, PaidFake(ledger), ledger))


def test_say_applies_normalization_and_lexicon(tmp_path, lexdir, capsys):
    ledger = Ledger(tmp_path / "r.json", month=lambda: "2026-11")
    eng = PaidFake(ledger)
    out = tmp_path / "x" / "s.m4a"
    say(
        "Henri IV est à Mespech.", eng, "v", out, serie="serie", lexiques=lexdir,
        opts=RunOptions(), ledger=ledger,
    )  # fmt: skip
    assert out.exists() and "Mespèque" in eng.calls[0] and "quatre" in eng.calls[0]
    assert ledger.used("paid") == len(eng.calls[0])
    # palier épuisé sans budget : refus avant l'appel
    eng2 = PaidFake(ledger)
    eng2.free_chars_per_month = 5
    with pytest.raises(BuildRefused):
        say("Une phrase de plus de cinq caractères.", eng2, "v", out, serie=None,
            lexiques=lexdir, opts=RunOptions(), ledger=ledger)  # fmt: skip
    assert eng2.calls == []


def test_voice_label():
    assert voice_label("fr-FR-Chirp3-HD-Aoede") == "Aoede"
    assert voice_label("fr-FR-VivienneMultilingualNeural") == "Vivienne"


def test_cli_build_estimate_end_to_end(tmp_path, monkeypatch, capsys, lexdir):
    """Chemin CLI complet avec un moteur gratuit enregistré dans le registre des moteurs."""
    from livres_tts import engines

    engines.register("free", "test_build", "FreeFake")
    proj = make_project(tmp_path)
    reg = tmp_path / "reg.json"
    args = [str(proj), "--moteur", "free", "--lexiques", str(lexdir), "--registre", str(reg)]
    assert main(["estimate", *args]) == 0
    assert "coût 0 €" in capsys.readouterr().out
    assert main(["build", *args]) == 0
    assert sorted(p.name for p in (proj / "sortie").glob("*.m4a")) == [
        "01 - Chapitre 1.m4a",
        "02 - Chapitre 2.m4a",
    ]
    assert not reg.exists()  # moteur gratuit : le registre n'est pas touché
    assert main(["fix", str(proj), "--registre", str(reg)]) == 0
    assert "Rien à faire" in capsys.readouterr().out
