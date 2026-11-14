"""Azure AI Speech (REST, SSML), voix neuronales fr-FR. Palier gratuit F0.

Variables : AZURE_SPEECH_KEY (en-tête Ocp-Apim-Subscription-Key), AZURE_SPEECH_REGION
(défaut westeurope). Tarif : voir docs/veille-voix.md (HD ≈ 22 USD / M, Neural ≈ 15-16 USD ;
F0 : 500 000 car. neuronaux gratuits par mois, HD inclus ou non : à vérifier).
"""

from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Any
from xml.sax.saxutils import escape

from ._http import HttpEngine, eur_per_million
from .base import EngineUnavailable

OUTPUT_FORMAT = "riff-24khz-16bit-mono-pcm"  # WAV 24 kHz mono
VOICES = [
    "fr-FR-VivienneMultilingualNeural",
    "fr-FR-RemyMultilingualNeural",
    "fr-FR-DeniseNeural",
    "fr-FR-HenriNeural",
    "fr-FR-EloiseNeural",
    # Voix HD : nom construit d'après le motif en-US (« en-US-Ava:DragonHDLatestNeural »),
    # existence en fr-FR à confirmer (liste des voix du service).
    "fr-FR-Vivienne:DragonHDLatestNeural",
    "fr-FR-Remy:DragonHDLatestNeural",
]


class AzureEngine(HttpEngine):
    name = "azure"
    env_var = "AZURE_SPEECH_KEY"
    price_per_million_chars = eur_per_million(22.0)  # HD, plafond ; Neural ≈ 15-16 USD
    max_chars = 3000  # une requête est limitée à 10 min d'audio ; 3 000 car. ≈ 3 min
    default_voice = "fr-FR-VivienneMultilingualNeural"
    description = "Azure AI Speech neuronal fr-FR (en ligne, F0 : 500 k car. gratuits/mois)"

    def list_voices(self) -> list[str]:
        return list(VOICES)

    def _region(self) -> str:
        region = os.environ.get("AZURE_SPEECH_REGION", "").strip() or "westeurope"
        if not re.fullmatch(r"[a-z0-9]+", region):
            raise EngineUnavailable(f"azure : AZURE_SPEECH_REGION invalide ({region!r})")
        return region

    def synthesize(
        self,
        text: str,
        voice: str | None = None,
        params: dict[str, Any] | None = None,
        out_path: Path | None = None,
    ) -> Path:
        voice = voice or self.default_voice
        if not re.fullmatch(r"[A-Za-z0-9:\-_]+", voice):
            raise EngineUnavailable(f"azure : nom de voix invalide ({voice!r})")
        ssml = (
            "<speak version='1.0' xml:lang='fr-FR' "
            "xmlns='http://www.w3.org/2001/10/synthesis'>"
            f"<voice name='{voice}'>{escape(text)}</voice></speak>"
        )
        url = f"https://{self._region()}.tts.speech.microsoft.com/cognitiveservices/v1"
        resp = self._request(
            "POST",
            url,
            {
                "Ocp-Apim-Subscription-Key": self._key(),
                "Content-Type": "application/ssml+xml",
                "X-Microsoft-OutputFormat": OUTPUT_FORMAT,
            },
            content=ssml.encode("utf-8"),
        )
        return self._save_wav(resp.content, out_path)
