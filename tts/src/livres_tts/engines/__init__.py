"""Registre des moteurs. Les modules sont importés à la demande."""

from __future__ import annotations

import importlib

from .base import Engine, EngineUnavailable

# nom -> (module, classe)
_REGISTRY: dict[str, tuple[str, str]] = {
    "edge_tts": ("livres_tts.engines.edge_tts", "EdgeTtsEngine"),
    "piper": ("livres_tts.engines.piper", "PiperEngine"),
    "kokoro": ("livres_tts.engines.kokoro", "KokoroEngine"),
    "xtts": ("livres_tts.engines.xtts", "XttsEngine"),
    "f5": ("livres_tts.engines.f5", "F5Engine"),
    "chatterbox": ("livres_tts.engines.chatterbox", "ChatterboxEngine"),
    "kyutai": ("livres_tts.engines.kyutai", "KyutaiEngine"),
    # Moteurs en ligne payants (palier gratuit utilisable pour le banc d'essai)
    "google": ("livres_tts.engines.google", "GoogleEngine"),
    "azure": ("livres_tts.engines.azure", "AzureEngine"),
    "elevenlabs": ("livres_tts.engines.elevenlabs", "ElevenLabsEngine"),
    "mistral": ("livres_tts.engines.mistral", "MistralEngine"),
}


def engine_names() -> list[str]:
    return list(_REGISTRY)


def register(name: str, module: str, cls: str) -> None:
    _REGISTRY[name] = (module, cls)


def get_engine(name: str) -> Engine:
    if name not in _REGISTRY:
        raise KeyError(f"moteur inconnu : {name} (connus : {', '.join(_REGISTRY)})")
    module, cls = _REGISTRY[name]
    return getattr(importlib.import_module(module), cls)()


__all__ = ["Engine", "EngineUnavailable", "engine_names", "get_engine", "register"]
