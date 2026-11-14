"""ElevenLabs (modèle multilingue, API REST). Plan gratuit : 10 000 crédits/mois (≈ 1 crédit/car.).

Clé : ELEVENLABS_API_KEY (en-tête xi-api-key). Modèle : ELEVENLABS_MODEL (défaut
eleven_multilingual_v2). Les identifiants de voix ci-dessous sont des voix « premade » connues
de mémoire : à confirmer (GET /v1/voices) ; n'importe quel identifiant peut être passé en --voix.
Le plan gratuit n'a pas de licence commerciale : compatible avec l'usage privé du projet.
"""

from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Any

from ._http import HttpEngine, eur_per_million
from .base import EngineUnavailable

DEFAULT_MODEL = "eleven_multilingual_v2"
VOICES = {
    "Adam": "pNInz6obpgDQGcFmaJgB",
    "Rachel": "21m00Tcm4TlvDq8ikWAM",
    "Antoni": "ErXwobaYiN019PkySvjV",
    "Josh": "TxGEqnHWrfWFTfGW9XjX",
}


class ElevenLabsEngine(HttpEngine):
    name = "elevenlabs"
    env_var = "ELEVENLABS_API_KEY"
    # ≈ 220 USD / M (plan Creator : 22 USD pour 100 000 crédits), pire cas, non vérifié.
    price_per_million_chars = eur_per_million(220.0)
    max_chars = 5000  # multilingual_v2 accepte 10 000 (source tierce) ; v3 : 5 000
    default_voice = "Adam"
    description = "ElevenLabs multilingue (en ligne, plan gratuit 10 000 crédits/mois)"

    def list_voices(self) -> list[str]:
        return list(VOICES)

    def synthesize(
        self,
        text: str,
        voice: str | None = None,
        params: dict[str, Any] | None = None,
        out_path: Path | None = None,
    ) -> Path:
        voice = voice or self.default_voice
        voice_id = VOICES.get(voice, voice)
        if not re.fullmatch(r"[A-Za-z0-9]+", voice_id):
            raise EngineUnavailable(f"elevenlabs : identifiant de voix invalide ({voice!r})")
        body = {
            "text": text,
            "model_id": os.environ.get("ELEVENLABS_MODEL", "").strip() or DEFAULT_MODEL,
        }
        resp = self._request(
            "POST",
            f"https://api.elevenlabs.io/v1/text-to-speech/{voice_id}",
            {"xi-api-key": self._key(), "Accept": "audio/mpeg"},
            params={"output_format": "mp3_44100_128"},
            json=body,
        )
        return self._save_encoded(resp.content, ".mp3", out_path)
