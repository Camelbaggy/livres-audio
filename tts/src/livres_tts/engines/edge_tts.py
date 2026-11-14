"""Voix neuronales Microsoft Edge via la bibliothèque edge-tts (accès non officiel, gratuit)."""

from __future__ import annotations

import asyncio
import os
import tempfile
from pathlib import Path
from typing import Any

from ..audio import to_wav
from .base import Engine, EngineUnavailable

VOICES = [
    "fr-FR-RemyMultilingualNeural",
    "fr-FR-VivienneMultilingualNeural",
    "fr-FR-DeniseNeural",
    "fr-FR-HenriNeural",
    "fr-FR-EloiseNeural",
]


class EdgeTtsEngine(Engine):
    name = "edge_tts"
    max_chars = 3000
    default_voice = "fr-FR-RemyMultilingualNeural"
    description = "Voix neuronales Edge (en ligne, gratuit, non officiel)"

    def check_available(self) -> None:
        try:
            import edge_tts  # noqa: F401
        except ImportError as exc:
            raise EngineUnavailable("edge_tts : bibliothèque edge-tts absente") from exc

    def list_voices(self) -> list[str]:
        return list(VOICES)

    def synthesize(
        self,
        text: str,
        voice: str | None = None,
        params: dict[str, Any] | None = None,
        out_path: Path | None = None,
    ) -> Path:
        self.check_available()
        import edge_tts

        params = params or {}
        voice = voice or self.default_voice
        dst = self._out(out_path)

        async def _run(mp3: str) -> None:
            comm = edge_tts.Communicate(
                text,
                voice,
                rate=params.get("rate", "+0%"),
                pitch=params.get("pitch", "+0Hz"),
                # aiohttp n'utilise pas les variables de mandataire d'elles-mêmes.
                proxy=os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy"),
            )
            await comm.save(mp3)

        with tempfile.TemporaryDirectory() as tmp:
            mp3 = str(Path(tmp) / "out.mp3")
            try:
                asyncio.run(_run(mp3))
            except Exception as exc:  # réseau, hôte refusé, service indisponible
                raise EngineUnavailable(f"edge_tts : synthèse impossible ({exc})") from exc
            to_wav(Path(mp3), dst)
        return dst
