# Atelier de synthèse vocale (`tts/`)

Outil en ligne de commande, indépendant de l'application Android (voir `CLAUDE.md` §8).
État : lots T0 (`bench`) et T1 (`prepare`) disponibles ; `say`, `build`, `fix` (T2), `estimate`, `publish` (T3) à venir.

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

## Préparer un livre (`prepare`)

```bash
uv run tts prepare livre.epub [--sortie DOSSIER] [--garder-notes] [--niveau-chapitres N] \
    [--retirer-dedicaces]
```

EPUB **sans DRM** uniquement (un EPUB chiffré ou à `rights.xml` est refusé ; l'obfuscation de
polices n'est pas un DRM). Produit `projets/<nom>/` (ou `--sortie`) :

```
projet.json          métadonnées, chapitres, options, schemaVersion (relu par le lot T2)
chapitres/NN - Titre.txt         texte final normalisé, un paragraphe par ligne
chapitres-bruts/NN - Titre.txt   texte nettoyé, non normalisé (contrôle)
couverture.jpg|png   couverture de l'EPUB (extension d'origine)
rapport.html         chapitres, durées, passages retirés par type, avant/après
```

Dans les fichiers de chapitre : une ligne `* * *` est un séparateur de scène (à transformer en
silence) ; une ligne qui commence par `— ` est une réplique ; un titre se termine par un point.

**Chapitrage** (`epub/chapters.py`) : un chapitre par entrée de la table des matières de niveau
≤ N. Sans `--niveau-chapitres`, on retient le niveau **le plus fin** dont ≥ 80 % du texte tient
en chapitres de 3 à 60 minutes estimées (≈ 15 caractères/s). Un chapitre de moins de 300
caractères (« Première partie ») est fondu dans le suivant. Repli sur les titres h1–h6, puis sur
un chapitre par document.

**Nombres** (`text/numbers.py`) : orthographe traditionnelle (« mille cinq cent soixante-douze »,
« vingt et un », « quatre-vingts »), sans traits d'union entre centaines et milliers. Les
années sont lues comme des nombres (pas de « quinze cent »).

**Normalisation** (`text/normalize.py`) : chiffres romains en contexte seulement (rois et papes,
siècles, tome/chapitre/acte…, chiffre romain seul sur une ligne ≤ 150) ; abréviations (M., Mme,
St, etc., cf., av. J.-C.…) ; dates, heures, unités ; sigles listés dans une table
(`NormalizeOptions.acronyms`, seuls les sigles de la table sont épelés). La fonction est pure et
idempotente.

Limites connues : nombres séparés par une espace simple (« 10 000 ») lus comme un seul nombre si
les groupes font 3 chiffres ; un mot capitalisé suivi d'un chiffre romain n'est converti que pour
une liste de prénoms de souverains (`DEFAULT_REGNAL_NAMES`) ; « Ire » (Élisabeth Ire) n'est jamais
converti (ambigu avec le mot « ire »).

## Vérifications

```bash
uv run ruff check && uv run pytest
```
