"""Squelettes de moteurs payants (non fonctionnels, lot T3).

Tarifs indicatifs en euros par million de caractères : à revérifier (voir docs/veille-voix.md).
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from .base import Engine, EngineUnavailable


class _PaidStub(Engine):
    is_paid = True
    max_chars = 4000
    env_var = ""

    def check_available(self) -> None:
        raise EngineUnavailable(f"{self.name} : moteur payant pas encore disponible (lot T3)")

    def synthesize(
        self,
        text: str,
        voice: str | None = None,
        params: dict[str, Any] | None = None,
        out_path: Path | None = None,
    ) -> Path:
        self.check_available()
        raise AssertionError("inaccessible")


class OpenAiEngine(_PaidStub):
    name = "openai"
    price_per_million_chars = 15.0
    env_var = "OPENAI_API_KEY"
    description = "OpenAI TTS"


class GoogleEngine(_PaidStub):
    name = "google"
    price_per_million_chars = 30.0
    env_var = "GOOGLE_APPLICATION_CREDENTIALS"
    description = "Google Cloud Chirp 3 HD"


class AzureEngine(_PaidStub):
    name = "azure"
    price_per_million_chars = 22.0
    env_var = "AZURE_SPEECH_KEY"
    description = "Azure Neural HD"


class ElevenLabsEngine(_PaidStub):
    name = "elevenlabs"
    price_per_million_chars = 80.0
    env_var = "ELEVENLABS_API_KEY"
    description = "ElevenLabs"
