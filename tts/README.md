# Atelier de synthèse vocale (`tts/`)

Outil en ligne de commande, indépendant de l'application Android (voir `CLAUDE.md` §8).
État : lot T0 — seule la commande `bench` est disponible.

## Installation

Prérequis : Python 3.11+, [`uv`](https://docs.astral.sh/uv/), **ffmpeg** dans le PATH.

```bash
cd tts
uv sync                    # installation légère : edge-tts + piper
uv sync --extra kokoro     # moteurs lourds en option (kokoro, xtts, f5, chatterbox, kyutai)
uv run tts --help
```

## Banc d'essai

```bash
uv run tts bench extraits/test-perigord.txt --moteurs edge_tts,piper
uv run tts bench extraits/test-perigord.txt --moteurs edge_tts,kokoro \
    --voix edge_tts=fr-FR-RemyMultilingualNeural
uv run tts moteurs         # liste les moteurs et leur disponibilité
```

Résultat dans `bench-out/<date>/` : `voix-A.m4a`, `voix-B.m4a`… (ordre aléatoire),
`ecoute.html` (à ouvrir, noter, exporter le JSON) et `cle.json`
(**ne pas l'ouvrir avant d'avoir noté**). Les moteurs payants (`google`, `azure`, `elevenlabs`,
`mistral`) exigent `--autoriser-payant` ; le coût maximal estimé est affiché et le banc refuse de
démarrer au-delà de `--budget-max` (défaut 0,50 €). Clés : `GOOGLE_TTS_API_KEY`, `AZURE_SPEECH_KEY`
(+ `AZURE_SPEECH_REGION`), `ELEVENLABS_API_KEY`, `MISTRAL_API_KEY`. Guide : `docs/essai-voix-payantes.md`.

Veille des voix : `docs/veille-voix.md`.

## Vérifications

```bash
uv run ruff check && uv run pytest
```
