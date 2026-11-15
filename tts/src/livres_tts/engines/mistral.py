"""Mistral Voxtral TTS (API REST `POST /v1/audio/speech`, réponse JSON avec `audio_data` en base64).

Clé : MISTRAL_API_KEY (en-tête Authorization: Bearer). Modèle : MISTRAL_TTS_MODEL. Les noms de
modèle et de voix varient selon les pages de documentation (voxtral-mini-tts-2603,
voxtral-mini-tts-3-0, …) : défaut « voxtral-mini-tts-latest », à confirmer. Les voix se
choisissent par `voice_id` (liste : `list_remote_voices`, route non vérifiée).
"""

from __future__ import annotations

import base64
import os
from pathlib import Path
from typing import Any

from ._http import HttpEngine, eur_per_million
from .base import EngineUnavailable

ENDPOINT = "https://api.mistral.ai/v1/audio/speech"
DEFAULT_MODEL = "voxtral-mini-tts-latest"


class MistralEngine(HttpEngine):
    name = "mistral"
    env_var = "MISTRAL_API_KEY"
    price_per_million_chars = eur_per_million(16.0)  # 13 USD annoncé pour la version « 3 »
    max_chars = 2000  # limite non documentée trouvée : valeur prudente
    default_voice = "fr_marie_neutral"  # supposé (voix « Marie ») : à vérifier
    description = "Mistral Voxtral TTS (en ligne, Experiment sans carte ?)"

    def list_remote_voices(self) -> list[str]:
        resp = self._request(
            "GET",
            "https://api.mistral.ai/v1/audio/voices",
            {"Authorization": f"Bearer {self._key()}"},
            params={"type": "preset", "page_size": 100},
        )
        body = resp.json()
        items = body.get("items") or body.get("voices") or []
        return sorted(str(v.get("slug") or v.get("id") or v.get("name")) for v in items)

    def synthesize(
        self,
        text: str,
        voice: str | None = None,
        params: dict[str, Any] | None = None,
        out_path: Path | None = None,
    ) -> Path:
        body = {
            "model": os.environ.get("MISTRAL_TTS_MODEL", "").strip() or DEFAULT_MODEL,
            "input": text,
            "voice_id": voice or self.default_voice,
            "response_format": "wav",
        }
        resp = self._request(
            "POST", ENDPOINT, {"Authorization": f"Bearer {self._key()}"}, json=body
        )
        try:
            audio = base64.b64decode(resp.json()["audio_data"])
        except (ValueError, KeyError) as exc:
            raise EngineUnavailable("mistral : réponse sans audio_data exploitable") from exc
        return self._save_wav(audio, out_path)
