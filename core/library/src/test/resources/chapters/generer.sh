#!/usr/bin/env bash
# Régénère les fichiers de test des chapitres (essai E3). Nécessite ffmpeg et uv (mutagen non requis) et python3.
set -euo pipefail
cd "$(dirname "$0")"

META=$(mktemp)
cat > "$META" <<'M'
;FFMETADATA1
title=Livre d'essai
artist=Auteur Été
[CHAPTER]
TIMEBASE=1/1000
START=0
END=2000
title=Prologue : l'épée
[CHAPTER]
TIMEBASE=1/1000
START=2000
END=5500
title=Chapitre 2 — Œuvre à Châteauroux
[CHAPTER]
TIMEBASE=1/1000
START=5500
END=8000
title=Épilogue
M

SRC="-f lavfi -t 8 -i anullsrc=r=22050:cl=mono"
AAC="-c:a aac -b:a 16k"

# (1) QuickTime : piste de chapitres texte + tref/chap (ffmpeg écrit cela pour le muxer mov)
ffmpeg -y -loglevel error $SRC -i "$META" -map_metadata 1 -map_chapters 1 $AAC -f mov qt-chapitres.mov

# (2) Nero chpl : le muxer ipod (m4b) écrit l'atome chpl ; moov en fin de fichier (défaut)
ffmpeg -y -loglevel error $SRC -i "$META" -map_metadata 1 -map_chapters 1 $AAC -f ipod chpl-moov-fin.m4b

# (3) idem avec moov en début (faststart)
ffmpeg -y -loglevel error $SRC -i "$META" -map_metadata 1 -map_chapters 1 $AAC -movflags +faststart -f ipod chpl-moov-debut.m4b

# (4) MP3 avec CHAP/CTOC en ID3v2.4 puis ID3v2.3 (ffmpeg écrit les CHAP ; CTOC ajouté par mutagen)
ffmpeg -y -loglevel error $SRC -i "$META" -map_metadata 1 -map_chapters 1 -c:a libmp3lame -b:a 16k -id3v2_version 4 mp3-chap-v24.mp3
ffmpeg -y -loglevel error $SRC -i "$META" -map_metadata 1 -map_chapters 1 -c:a libmp3lame -b:a 16k -id3v2_version 3 mp3-chap-v23.mp3

# (3b) variantes « un seul mécanisme » : on neutralise l'autre en renommant son atome en « free » (même taille)
python3 -I - <<'PY'
d = open("chpl-moov-fin.m4b", "rb").read()
assert d.count(b"chpl") == 1 and d.count(b"chap") == 1
open("qt-seul.m4b", "wb").write(d.replace(b"chpl", b"free"))
open("chpl-seul.m4b", "wb").write(d.replace(b"chap", b"free"))
PY

# (5) sans chapitres
ffmpeg -y -loglevel error $SRC $AAC -f ipod sans-chapitres.m4b
ffmpeg -y -loglevel error $SRC -c:a libmp3lame -b:a 16k sans-chapitres.mp3

rm -f "$META"
ls -l
