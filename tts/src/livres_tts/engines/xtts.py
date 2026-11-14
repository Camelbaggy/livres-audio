"""XTTS-v2 (local, GPU recommandé, licence CPML non commerciale).

Extra optionnel : `uv sync --extra xtts`. Non testé dans l'environnement cloud.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from .base import Engine, EngineUnavailable


class XttsEngine(Engine):
    name = "xtts"
    max_chars = 230  # limite effective du modèle en français
    default_voice = "Claribel Dervla"
    extra = "xtts"
    description = "XTTS-v2 (local, GPU recommandé, licence non commerciale)"

    def check_available(self) -> None:
        try:
            import TTS  # noqa: F401
        except ImportError as exc:
            raise self._missing("coqui-tts") from exc

    def synthesize(
        self,
        text: str,
        voice: str | None = None,
        params: dict[str, Any] | None = None,
        out_path: Path | None = None,
    ) -> Path:
        self.check_available()
        from TTS.api import TTS

        dst = self._out(out_path)
        try:
            tts = TTS("tts_models/multilingual/multi-dataset/xtts_v2")
            tts.tts_to_file(
                text=text, speaker=voice or self.default_voice, language="fr", file_path=str(dst)
            )
        except Exception as exc:
            raise EngineUnavailable(f"xtts : synthèse impossible ({exc})") from exc
        return dst
