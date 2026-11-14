"""Kyutai TTS 1.6B en/fr (local, GPU, CC-BY 4.0).

Extra optionnel : `uv sync --extra kyutai`. Squelette : la synthèse reste à écrire et à
tester sur une machine équipée (lot T0, après veille).
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from .base import Engine, EngineUnavailable


class KyutaiEngine(Engine):
    name = "kyutai"
    max_chars = 500
    extra = "kyutai"
    description = "Kyutai TTS 1.6B en/fr (local, GPU, CC-BY 4.0)"

    def check_available(self) -> None:
        try:
            __import__("moshi")
        except ImportError as exc:
            raise self._missing("moshi") from exc

    def synthesize(
        self,
        text: str,
        voice: str | None = None,
        params: dict[str, Any] | None = None,
        out_path: Path | None = None,
    ) -> Path:
        self.check_available()
        raise EngineUnavailable("kyutai : synthèse pas encore écrite (squelette du lot T0)")
