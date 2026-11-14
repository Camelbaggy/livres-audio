"""Google Cloud Text-to-Speech, voix Chirp 3 HD (clé d'API, palier gratuit 1 M car./mois).

Documentation : https://docs.cloud.google.com/text-to-speech/docs/reference/rest/v1/text/synthesize
Tarif (https://cloud.google.com/text-to-speech/pricing, relevé le 2026-11-14) : Chirp 3 HD
30 USD / M caractères, 1 M gratuit par mois ; Neural2 16 USD ; Standard/WaveNet 4 USD.
Limite : 5 000 octets de texte par requête ; on se tient à 2 000 caractères (le français
accentué pèse jusqu'à 2 octets par caractère, donc 4 000 octets au pire).
"""

from __future__ import annotations

import base64
from pathlib import Path
from typing import Any

from ._http import HttpEngine, eur_per_million
from .base import EngineUnavailable

ENDPOINT = "https://texttospeech.googleapis.com/v1/text:synthesize"
VOICES_ENDPOINT = "https://texttospeech.googleapis.com/v1/voices"
MAX_BYTES = 5000

# Noms de voix Chirp 3 HD confirmés par la documentation (30 voix, disponibles en fr-FR).
CHIRP_NAMES = ["Aoede", "Charon", "Kore", "Leda", "Orus", "Puck", "Zephyr"]
# Voix plus anciennes, de mémoire : à confirmer avec list_remote_voices().
OTHER_VOICES = ["fr-FR-Neural2-A", "fr-FR-Neural2-B", "fr-FR-Studio-A", "fr-FR-Studio-D"]


class GoogleEngine(HttpEngine):
    name = "google"
    env_var = "GOOGLE_TTS_API_KEY"
    price_per_million_chars = eur_per_million(30.0)  # Chirp 3 HD
    max_chars = 2000
    default_voice = "fr-FR-Chirp3-HD-Charon"
    description = "Google Cloud Chirp 3 HD (en ligne, 1 M car. gratuits/mois)"

    def list_voices(self) -> list[str]:
        return [f"fr-FR-Chirp3-HD-{n}" for n in CHIRP_NAMES] + OTHER_VOICES

    def list_remote_voices(self) -> list[str]:
        """Voix fr-FR réellement proposées par le service (appel gratuit)."""
        resp = self._request(
            "GET",
            VOICES_ENDPOINT,
            {"X-Goog-Api-Key": self._key()},
            params={"languageCode": "fr-FR"},
        )
        return sorted(v["name"] for v in resp.json().get("voices", []))

    def synthesize(
        self,
        text: str,
        voice: str | None = None,
        params: dict[str, Any] | None = None,
        out_path: Path | None = None,
    ) -> Path:
        if len(text.encode("utf-8")) > MAX_BYTES:
            raise EngineUnavailable(f"google : texte trop long (> {MAX_BYTES} octets)")
        params = params or {}
        body = {
            "input": {"text": text},
            "voice": {"languageCode": "fr-FR", "name": voice or self.default_voice},
            "audioConfig": {
                "audioEncoding": "LINEAR16",  # WAV avec en-tête
                "sampleRateHertz": 24000,
                "speakingRate": params.get("speed", 1.0),
            },
        }
        resp = self._request("POST", ENDPOINT, {"X-Goog-Api-Key": self._key()}, json=body)
        try:
            audio = base64.b64decode(resp.json()["audioContent"])
        except (ValueError, KeyError) as exc:
            raise EngineUnavailable("google : réponse sans audioContent exploitable") from exc
        return self._save_wav(audio, out_path)
