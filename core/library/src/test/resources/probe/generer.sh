#!/usr/bin/env bash
# Régénère les fichiers de test du sondage (tâche 1.5). Nécessite ffmpeg. Contenu : 10 s de silence, étiquettes fictives.
set -euo pipefail
cd "$(dirname "$0")"

# Couverture bruitée (~200 Ko) : sert à vérifier qu'elle n'est jamais lue par le sondage.
ffmpeg -y -loglevel error -f lavfi -i "color=c=gray:s=400x400,geq=lum='random(1)*255':cb=128:cr=128" -frames:v 1 -q:v 6 cover.jpg

SRC="-f lavfi -t 10 -i anullsrc=r=44100:cl=mono"
TAGS=(-metadata "title=Chapitre un" -metadata "album=Le Livre d'essai" -metadata "artist=Auteur Été"
      -metadata "album_artist=Auteur Été" -metadata "composer=Narrateur Œil" -metadata "track=3/12"
      -metadata "disc=2/3" -metadata "date=2014-05-06" -metadata "comment=Un résumé fictif.")

# M4B : étiquettes + couverture (covr) ; chapitres Nero via le muxer ipod.
ffmpeg -y -loglevel error $SRC -i cover.jpg -map 0:a -map 1:v -c:a aac -b:a 16k -c:v copy \
  -disposition:v:0 attached_pic "${TAGS[@]}" -f ipod complet.m4b
# idem avec moov en tête.
ffmpeg -y -loglevel error $SRC -i cover.jpg -map 0:a -map 1:v -c:a aac -b:a 16k -c:v copy \
  -disposition:v:0 attached_pic "${TAGS[@]}" -movflags +faststart -f ipod complet-faststart.m4b

# MP3 CBR avec en-tête Info (défaut de LAME), ID3v2.3, couverture, TXXX série / narrateur.
ffmpeg -y -loglevel error $SRC -i cover.jpg -map 0:a -map 1:v -c:a libmp3lame -b:a 32k -c:v copy \
  -id3v2_version 3 "${TAGS[@]}" -metadata "SERIES=La Saga" -metadata "SERIES-PART=2.5" -metadata "NARRATOR=Voix Claire" \
  cbr-info-v23.mp3
# MP3 CBR sans en-tête Xing/Info : durée estimée par débit × taille. ID3v2.4.
ffmpeg -y -loglevel error $SRC -c:a libmp3lame -b:a 32k -write_xing 0 -id3v2_version 4 "${TAGS[@]}" cbr-sans-xing-v24.mp3
# MP3 VBR avec en-tête Xing.
ffmpeg -y -loglevel error -f lavfi -t 10 -i "sine=frequency=440:sample_rate=44100" -c:a libmp3lame -q:a 6 -id3v2_version 3 \
  "${TAGS[@]}" vbr-xing.mp3
# MP3 sans aucune étiquette ni couverture.
ffmpeg -y -loglevel error $SRC -c:a libmp3lame -b:a 32k -write_id3v2 0 -write_xing 0 nu.mp3
# MPEG-2 (22,05 kHz) pour la table des débits / trames de 576 échantillons.
ffmpeg -y -loglevel error -f lavfi -t 10 -i "anullsrc=r=22050:cl=mono" -c:a libmp3lame -b:a 24k -write_id3v2 0 mpeg2.mp3
# Trois pistes d'un même livre (2 s), pour l'ordre par numéros de piste.
for n in 1 2 3; do
  ffmpeg -y -loglevel error -f lavfi -t 2 -i "anullsrc=r=44100:cl=mono" -c:a libmp3lame -b:a 32k -id3v2_version 3 \
    -metadata "title=Partie $n" -metadata "album=Album Multi" -metadata "artist=Auteur Multi" -metadata "track=$n/3" \
    piste$n.mp3
done
ls -l
