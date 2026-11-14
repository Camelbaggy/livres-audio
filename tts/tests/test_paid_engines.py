"""Moteurs en ligne payants : tests sans réseau (httpx.MockTransport)."""

from __future__ import annotations

import base64
import json
import wave

import httpx
import pytest

from livres_tts import bench as bench_mod
from livres_tts.engines import EngineUnavailable, get_engine
from livres_tts.engines.azure import AzureEngine
from livres_tts.engines.elevenlabs import ElevenLabsEngine
from livres_tts.engines.google import GoogleEngine
from livres_tts.engines.mistral import MistralEngine

KEY = "cle-factice-xyz"


def wav_bytes(tmp_path, frames: int = 240) -> bytes:
    p = tmp_path / "ref.wav"
    with wave.open(str(p), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(24000)
        w.writeframes(b"\x01\x02" * frames)
    return p.read_bytes()


def recorder(response_fn):
    seen: list[httpx.Request] = []

    def handler(req: httpx.Request) -> httpx.Response:
        seen.append(req)
        return response_fn(req)

    return httpx.MockTransport(handler), seen


@pytest.fixture(autouse=True)
def _keys(monkeypatch):
    for var in (
        "GOOGLE_TTS_API_KEY",
        "AZURE_SPEECH_KEY",
        "ELEVENLABS_API_KEY",
        "MISTRAL_API_KEY",
    ):
        monkeypatch.setenv(var, KEY)
    monkeypatch.delenv("AZURE_SPEECH_REGION", raising=False)


def assert_key_only_in_header(req: httpx.Request, header: str, value: str = KEY):
    assert req.headers[header] == value
    assert KEY not in str(req.url)
    assert KEY.encode() not in req.content


def test_google_request_and_base64_decoding(tmp_path):
    audio = wav_bytes(tmp_path)
    transport, seen = recorder(
        lambda r: httpx.Response(200, json={"audioContent": base64.b64encode(audio).decode()})
    )
    out = GoogleEngine(transport).synthesize("Bonjour à tous.", None, None, tmp_path / "o.wav")
    assert out.read_bytes() == audio
    req = seen[0]
    assert str(req.url) == "https://texttospeech.googleapis.com/v1/text:synthesize"
    assert_key_only_in_header(req, "X-Goog-Api-Key")
    body = json.loads(req.content)
    assert body["voice"] == {"languageCode": "fr-FR", "name": "fr-FR-Chirp3-HD-Charon"}
    assert body["input"]["text"] == "Bonjour à tous."


def test_google_rejects_oversized_input():
    with pytest.raises(EngineUnavailable, match="trop long"):
        GoogleEngine(httpx.MockTransport(lambda r: httpx.Response(500))).synthesize("é" * 2600)


def test_google_max_chars_keeps_request_under_byte_limit():
    eng = GoogleEngine()
    assert len(("é" * eng.max_chars).encode()) <= 5000


def test_google_http_error_is_typed_and_hides_key():
    transport, _ = recorder(
        lambda r: httpx.Response(
            403, json={"error": {"message": f"API key {KEY} not valid", "code": 403}}
        )
    )
    with pytest.raises(EngineUnavailable) as exc:
        GoogleEngine(transport).synthesize("Bonjour.")
    msg = str(exc.value)
    assert "HTTP 403" in msg and "not valid" in msg and KEY not in msg


def test_azure_request_ssml_and_region(tmp_path, monkeypatch):
    monkeypatch.setenv("AZURE_SPEECH_REGION", "francecentral")
    audio = wav_bytes(tmp_path)
    transport, seen = recorder(lambda r: httpx.Response(200, content=audio))
    out = AzureEngine(transport).synthesize("Siorac & <fils>", None, None, tmp_path / "o.wav")
    assert out.read_bytes() == audio
    req = seen[0]
    assert req.url.host == "francecentral.tts.speech.microsoft.com"
    assert req.url.path == "/cognitiveservices/v1"
    assert_key_only_in_header(req, "Ocp-Apim-Subscription-Key")
    assert req.headers["Content-Type"] == "application/ssml+xml"
    assert req.headers["X-Microsoft-OutputFormat"] == "riff-24khz-16bit-mono-pcm"
    assert b"Siorac &amp; &lt;fils&gt;" in req.content  # SSML échappé
    assert b"fr-FR-VivienneMultilingualNeural" in req.content


def test_azure_default_region_and_bad_region(tmp_path, monkeypatch):
    transport, seen = recorder(lambda r: httpx.Response(200, content=wav_bytes(tmp_path)))
    AzureEngine(transport).synthesize("Bonjour.", None, None, tmp_path / "o.wav")
    assert seen[0].url.host == "westeurope.tts.speech.microsoft.com"
    monkeypatch.setenv("AZURE_SPEECH_REGION", "evil.com/x")
    with pytest.raises(EngineUnavailable, match="REGION"):
        AzureEngine(transport).synthesize("Bonjour.")


def test_azure_http_error_message():
    transport, _ = recorder(lambda r: httpx.Response(401, text="Access denied"))
    with pytest.raises(EngineUnavailable, match="HTTP 401 — Access denied"):
        AzureEngine(transport).synthesize("Bonjour.")


def test_elevenlabs_request_and_binary_decoding(tmp_path, monkeypatch):
    transport, seen = recorder(lambda r: httpx.Response(200, content=b"ID3-faux-mp3"))
    converted: list[bytes] = []

    def fake_to_wav(src, dst, sample_rate=24000):
        converted.append(src.read_bytes())
        dst.write_bytes(b"RIFFwav")
        return dst

    monkeypatch.setattr("livres_tts.engines._http.to_wav", fake_to_wav)
    out = ElevenLabsEngine(transport).synthesize("Bonjour.", "Rachel", None, tmp_path / "o.wav")
    assert out.read_bytes() == b"RIFFwav" and converted == [b"ID3-faux-mp3"]
    req = seen[0]
    assert req.url.path == "/v1/text-to-speech/21m00Tcm4TlvDq8ikWAM"
    assert_key_only_in_header(req, "xi-api-key")
    assert json.loads(req.content) == {"text": "Bonjour.", "model_id": "eleven_multilingual_v2"}


def test_elevenlabs_rejects_path_injection_in_voice():
    with pytest.raises(EngineUnavailable, match="voix invalide"):
        ElevenLabsEngine(httpx.MockTransport(lambda r: httpx.Response(200))).synthesize(
            "x", "../v1/user"
        )


def test_mistral_request_and_base64_decoding(tmp_path):
    audio = wav_bytes(tmp_path)
    transport, seen = recorder(
        lambda r: httpx.Response(200, json={"audio_data": base64.b64encode(audio).decode()})
    )
    out = MistralEngine(transport).synthesize("Bonjour.", "ma_voix", None, tmp_path / "o.wav")
    assert out.read_bytes() == audio
    req = seen[0]
    assert str(req.url) == "https://api.mistral.ai/v1/audio/speech"
    assert_key_only_in_header(req, "Authorization", f"Bearer {KEY}")
    body = json.loads(req.content)
    assert body["voice_id"] == "ma_voix" and body["input"] == "Bonjour."


@pytest.mark.parametrize(
    "name,var",
    [
        ("google", "GOOGLE_TTS_API_KEY"),
        ("azure", "AZURE_SPEECH_KEY"),
        ("elevenlabs", "ELEVENLABS_API_KEY"),
        ("mistral", "MISTRAL_API_KEY"),
    ],
)
def test_missing_key_is_clear(name, var, monkeypatch):
    monkeypatch.delenv(var)
    eng = get_engine(name)
    with pytest.raises(EngineUnavailable, match=f"définir {var}"):
        eng.check_available()
    with pytest.raises(EngineUnavailable, match=var):
        eng.synthesize("Bonjour.")


def test_network_failure_is_typed():
    def boom(request):
        raise httpx.ConnectError("refusé")

    with pytest.raises(EngineUnavailable, match="ConnectError"):
        GoogleEngine(httpx.MockTransport(boom)).synthesize("Bonjour.")


def test_engine_metadata():
    for name in ("google", "azure", "elevenlabs", "mistral"):
        eng = get_engine(name)
        assert eng.is_paid and eng.price_per_million_chars > 0
        assert eng.max_chars and eng.default_voice
    assert get_engine("google").price_per_million_chars == pytest.approx(25.8)
    assert get_engine("google").default_voice in get_engine("google").list_voices()


def test_bench_budget_guard_blocks_before_any_call(tmp_path):
    eng = GoogleEngine(httpx.MockTransport(lambda r: pytest.fail("appel réseau inattendu")))
    text = "Une phrase de test. " * 100  # 2 000 caractères ≈ 0,05 €
    logs: list[str] = []
    with pytest.raises(bench_mod.BudgetExceeded, match="--budget-max"):
        bench_mod.run_bench(
            text,
            ["google"],
            tmp_path,
            allow_paid=True,
            budget_max=0.01,
            engine_factory=lambda n: eng,
            log=logs.append,
        )
    assert any("caractères" in line and "€" in line for line in logs)


def test_bench_estimate_and_default_budget():
    chars, cost = bench_mod.estimate_cost(GoogleEngine(), "Bonjour. " * 300)
    assert chars > 2000 and cost == pytest.approx(chars * 25.8 / 1e6)
    assert bench_mod.DEFAULT_BUDGET_MAX == 0.50


def test_cli_budget_guard(tmp_path, capsys, monkeypatch):
    from livres_tts.cli import main

    (tmp_path / "x.txt").write_text("Phrase. " * 600, "utf-8")  # ≈ 4 800 car. → ElevenLabs > 0,5 €
    code = main(
        [
            "bench",
            str(tmp_path / "x.txt"),
            "--moteurs",
            "elevenlabs",
            "--autoriser-payant",
            "--sortie",
            str(tmp_path / "o"),
        ]
    )
    assert code == 2
    assert "budget-max" in capsys.readouterr().err
