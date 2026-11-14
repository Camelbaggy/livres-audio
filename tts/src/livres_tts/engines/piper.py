"""Piper (local, CPU, licence MIT pour le moteur ; licences variables selon les voix)."""

from __future__ import annotations

import os
import urllib.request
import wave
from pathlib import Path
from typing import Any

from .base import Engine, EngineUnavailable

BASE_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main"
VOICES = {
    "fr_FR-siwis-medium": "fr/fr_FR/siwis/medium",
    "fr_FR-upmc-medium": "fr/fr_FR/upmc/medium",
    "fr_FR-tom-medium": "fr/fr_FR/tom/medium",
    "fr_FR-gilles-low": "fr/fr_FR/gilles/low",
}


def cache_dir() -> Path:
    base = os.environ.get("TTS_CACHE_DIR") or os.path.join(Path.home(), ".cache", "livres-tts")
    path = Path(base) / "piper"
    path.mkdir(parents=True, exist_ok=True)
    return path


class PiperEngine(Engine):
    name = "piper"
    max_chars = None
    default_voice = "fr_FR-siwis-medium"
    description = "Piper (local, CPU)"

    def check_available(self) -> None:
        try:
            import piper  # noqa: F401
        except ImportError as exc:
            raise EngineUnavailable("piper : bibliothèque piper-tts absente") from exc

    def list_voices(self) -> list[str]:
        return list(VOICES)

    def _model_path(self, voice: str) -> Path:
        if voice not in VOICES:
            raise EngineUnavailable(f"piper : voix inconnue « {voice} » ({', '.join(VOICES)})")
        folder = cache_dir()
        onnx = folder / f"{voice}.onnx"
        for suffix in (".onnx", ".onnx.json"):
            target = folder / f"{voice}{suffix}"
            if not target.exists():
                url = f"{BASE_URL}/{VOICES[voice]}/{voice}{suffix}"
                try:
                    urllib.request.urlretrieve(url, target)  # noqa: S310
                except Exception as exc:
                    target.unlink(missing_ok=True)
                    host = url.split("/")[2]
                    raise EngineUnavailable(
                        f"piper : téléchargement de la voix impossible ({host} : {exc})"
                    ) from exc
        return onnx

    def synthesize(
        self,
        text: str,
        voice: str | None = None,
        params: dict[str, Any] | None = None,
        out_path: Path | None = None,
    ) -> Path:
        self.check_available()
        from piper import PiperVoice

        model = self._model_path(voice or self.default_voice)
        dst = self._out(out_path)
        pv = PiperVoice.load(str(model))
        with wave.open(str(dst), "wb") as wav:
            pv.synthesize_wav(text, wav)
        return dst
