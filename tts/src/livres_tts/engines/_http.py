"""Socle commun des moteurs en ligne payants (HTTP via httpx, clé uniquement en en-tête).

httpx (et non urllib) : respecte `HTTPS_PROXY` / `SSL_CERT_FILE`, gère proprement les délais
d'attente et offre `httpx.MockTransport` pour tester sans réseau.

Taux de conversion utilisé pour tous les tarifs : 1 USD = 0,86 EUR (hypothèse du 2026-11-14,
à réviser ; sert uniquement à estimer un plafond de dépense).
"""

from __future__ import annotations

import os
import wave
from pathlib import Path
from typing import Any

import httpx

from ..audio import to_wav
from .base import Engine, EngineUnavailable

USD_TO_EUR = 0.86
USER_AGENT = "livres-tts/0.1"
TIMEOUT = httpx.Timeout(120.0, connect=15.0)


class HttpEngine(Engine):
    """Moteur en ligne : clé lue dans `env_var`, envoyée seulement dans un en-tête."""

    is_paid = True
    env_var = ""

    def __init__(self, transport: httpx.BaseTransport | None = None) -> None:
        self._transport = transport  # injecté par les tests

    # -- clé ---------------------------------------------------------------------------------
    def _key(self) -> str:
        key = os.environ.get(self.env_var, "").strip()
        if not key:
            raise EngineUnavailable(f"{self.name} : définir {self.env_var} (clé d'API absente)")
        return key

    def check_available(self) -> None:
        self._key()

    # -- HTTP --------------------------------------------------------------------------------
    def _request(
        self, method: str, url: str, headers: dict[str, str], **kwargs: Any
    ) -> httpx.Response:
        key = self._key()
        headers = {"User-Agent": USER_AGENT, **headers}
        try:
            with httpx.Client(transport=self._transport, timeout=TIMEOUT) as client:
                resp = client.request(method, url, headers=headers, **kwargs)
        except httpx.HTTPError as exc:  # réseau, délai, hôte refusé par le mandataire
            raise EngineUnavailable(
                f"{self.name} : requête impossible ({type(exc).__name__})"
            ) from exc
        if resp.status_code >= 400:
            raise EngineUnavailable(
                f"{self.name} : HTTP {resp.status_code} — "
                f"{_service_message(resp).replace(key, '***')}"
            )
        return resp

    # -- audio -------------------------------------------------------------------------------
    def _save_wav(self, data: bytes, out_path: Path | None) -> Path:
        """Écrit des octets WAV (avec en-tête RIFF) tels quels."""
        if data[:4] != b"RIFF":
            raise EngineUnavailable(f"{self.name} : la réponse n'est pas un fichier WAV")
        dst = self._out(out_path)
        dst.write_bytes(data)
        return dst

    def _save_encoded(self, data: bytes, suffix: str, out_path: Path | None) -> Path:
        """Convertit (ffmpeg) un MP3/Opus/… en WAV."""
        dst = self._out(out_path)
        src = dst.with_suffix(suffix)
        src.write_bytes(data)
        try:
            to_wav(src, dst)
        finally:
            src.unlink(missing_ok=True)
        return dst

    def _save_pcm(self, data: bytes, rate: int, out_path: Path | None) -> Path:
        dst = self._out(out_path)
        with wave.open(str(dst), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(rate)
            w.writeframes(data)
        return dst


def _service_message(resp: httpx.Response) -> str:
    """Message d'erreur du service (JSON usuel, sinon texte brut), tronqué."""
    text = resp.text.strip()
    try:
        body = resp.json()
        if isinstance(body, dict):
            for path in (("error", "message"), ("detail", "message"), ("detail",), ("message",)):
                cur: Any = body
                for part in path:
                    cur = cur.get(part) if isinstance(cur, dict) else None
                if isinstance(cur, str) and cur:
                    text = cur
                    break
    except ValueError:
        pass
    return text[:300] or resp.reason_phrase


def eur_per_million(usd_per_million: float) -> float:
    return round(usd_per_million * USD_TO_EUR, 2)
