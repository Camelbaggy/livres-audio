"""Kokoro-82M (local, Apache 2.0). Extra optionnel : `uv sync --extra kokoro`.

Nécessite aussi espeak-ng sur le système. Non testé dans l'environnement cloud.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from .base import Engine, EngineUnavailable


class KokoroEngine(Engine):
    name = "kokoro"
    max_chars = 1500
    default_voice = "ff_siwis"
    extra = "kokoro"
    description = "Kokoro-82M (local, léger, une seule voix française)"

    def check_available(self) -> None:
        try:
            import kokoro  # noqa: F401
            import soundfile  # noqa: F401
        except ImportError as exc:
            raise self._missing("kokoro / soundfile") from exc

    def synthesize(
        self,
        text: str,
        voice: str | None = None,
        params: dict[str, Any] | None = None,
        out_path: Path | None = None,
    ) -> Path:
        self.check_available()
        import numpy as np
        import soundfile as sf
        from kokoro import KPipeline

        params = params or {}
        try:
            pipeline = KPipeline(lang_code="f")
            chunks = [
                np.asarray(audio)
                for _, _, audio in pipeline(
                    text, voice=voice or self.default_voice, speed=params.get("speed", 1.0)
                )
            ]
        except Exception as exc:
            raise EngineUnavailable(f"kokoro : synthèse impossible ({exc})") from exc
        dst = self._out(out_path)
        sf.write(str(dst), np.concatenate(chunks), 24000)
        return dst
