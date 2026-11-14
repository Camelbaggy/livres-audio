"""F5-TTS (local, GPU recommandé ; variante française à fournir).

Extra optionnel : `uv sync --extra f5`. Squelette : la synthèse reste à écrire et à
tester sur une machine équipée (lot T0, après veille).
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from .base import Engine, EngineUnavailable


class F5Engine(Engine):
    name = "f5"
    max_chars = 500
    extra = "f5"
    description = "F5-TTS (local, GPU recommandé ; variante française à fournir)"

    def check_available(self) -> None:
        try:
            __import__("f5_tts")
        except ImportError as exc:
            raise self._missing("f5-tts") from exc

    def synthesize(
        self,
        text: str,
        voice: str | None = None,
        params: dict[str, Any] | None = None,
        out_path: Path | None = None,
    ) -> Path:
        self.check_available()
        raise EngineUnavailable("f5 : synthèse pas encore écrite (squelette du lot T0)")
