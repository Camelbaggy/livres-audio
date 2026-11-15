"""Interface commune des moteurs de synthèse (un moteur = un fichier)."""

from __future__ import annotations

import tempfile
from abc import ABC, abstractmethod
from pathlib import Path
from typing import Any


class EngineUnavailable(Exception):
    """Dépendance, modèle ou clé manquant : le moteur ne peut pas être utilisé."""


class Engine(ABC):
    name: str = ""
    is_paid: bool = False
    price_per_million_chars: float = 0.0  # en euros, 0 pour les moteurs gratuits
    max_chars: int | None = None  # longueur maximale d'un appel (None = illimitée)
    free_chars_per_month: int = 0  # palier gratuit mensuel déclaré (moteurs payants)
    supports_ipa: bool = False  # accepte une prononciation IPA (SSML <phoneme>)
    default_voice: str = ""
    extra: str | None = None  # nom de l'extra pyproject à installer
    description: str = ""

    def check_available(self) -> None:
        """Lève EngineUnavailable avec un message clair si le moteur est inutilisable."""
        return None

    def list_voices(self) -> list[str]:
        return [self.default_voice] if self.default_voice else []

    @abstractmethod
    def synthesize(
        self,
        text: str,
        voice: str | None = None,
        params: dict[str, Any] | None = None,
        out_path: Path | None = None,
    ) -> Path:
        """Synthétise `text` et renvoie le chemin d'un fichier WAV."""

    def _out(self, out_path: Path | None) -> Path:
        if out_path is not None:
            return out_path
        fd, name = tempfile.mkstemp(suffix=".wav", prefix=f"{self.name}-")
        import os

        os.close(fd)
        return Path(name)

    def _missing(self, module: str) -> EngineUnavailable:
        hint = f" (installer avec : uv sync --extra {self.extra})" if self.extra else ""
        return EngineUnavailable(f"{self.name} : dépendance « {module} » absente{hint}")
