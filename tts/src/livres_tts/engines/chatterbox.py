"""Chatterbox multilingue (local, GPU recommandé, MIT).

Extra optionnel : `uv sync --extra chatterbox`. Squelette : la synthèse reste à écrire et à
tester sur une machine équipée (lot T0, après veille).
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from .base import Engine, EngineUnavailable


class ChatterboxEngine(Engine):
    name = "chatterbox"
    max_chars = 500
    extra = "chatterbox"
    description = "Chatterbox multilingue (local, GPU recommandé, MIT)"

    def check_available(self) -> None:
        try:
            __import__("chatterbox")
        except ImportError as exc:
            raise self._missing("chatterbox-tts") from exc

    def synthesize(
        self,
        text: str,
        voice: str | None = None,
        params: dict[str, Any] | None = None,
        out_path: Path | None = None,
    ) -> Path:
        self.check_available()
        raise EngineUnavailable("chatterbox : synthèse pas encore écrite (squelette du lot T0)")
