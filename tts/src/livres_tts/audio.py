"""Utilitaires audio fondés sur ffmpeg."""

from __future__ import annotations

import shutil
import subprocess
from pathlib import Path


class FfmpegError(RuntimeError):
    pass


def require_ffmpeg() -> str:
    path = shutil.which("ffmpeg")
    if path is None:
        raise FfmpegError("ffmpeg est introuvable dans le PATH (requis par l'atelier).")
    return path


def run_ffmpeg(args: list[str]) -> None:
    cmd = [require_ffmpeg(), "-hide_banner", "-loglevel", "error", "-y", *args]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise FfmpegError(f"ffmpeg a échoué : {res.stderr.strip()}")


def to_wav(src: Path, dst: Path, sample_rate: int = 24000) -> Path:
    run_ffmpeg(["-i", str(src), "-ac", "1", "-ar", str(sample_rate), str(dst)])
    return dst


def concat_and_encode(parts: list[Path], dst: Path, silence_s: float = 0.3) -> Path:
    """Concatène des fichiers audio, normalise (EBU R128) et encode en M4A AAC mono."""
    if not parts:
        raise ValueError("aucun fichier à concaténer")
    cmd_in: list[str] = []
    for p in parts:
        cmd_in += ["-i", str(p)]
    n = len(parts)
    labels = "".join(f"[{i}:a]" for i in range(n))
    filt = (
        f"{labels}concat=n={n}:v=0:a=1,aresample=44100,"
        f"apad=pad_dur={silence_s},loudnorm=I=-19:TP=-1.5:LRA=11"
    )
    run_ffmpeg(
        [*cmd_in, "-filter_complex", filt, "-ac", "1", "-c:a", "aac", "-b:a", "64k", str(dst)]
    )
    return dst


# -- lot T2 : cache, assemblage, étiquetage ---------------------------------------------------
CACHE_RATE = 24000
LOUDNORM = "I=-19:TP=-1.5:LRA=11"


def to_flac(src: Path, dst: Path) -> Path:
    """Convertit un WAV en FLAC mono 24 kHz (cache par segment : moitié moins gros que le WAV)."""
    run_ffmpeg(["-i", str(src), "-ac", "1", "-ar", str(CACHE_RATE), "-f", "flac", str(dst)])
    return dst


def make_silence(dst: Path, seconds: float) -> Path:
    run_ffmpeg(
        ["-f", "lavfi", "-i", f"anullsrc=r={CACHE_RATE}:cl=mono", "-t", f"{seconds:.3f}"]
        + ["-f", "flac", str(dst)]
    )
    return dst


def concat_files(parts: list[Path], list_file: Path, dst_wav: Path) -> Path:
    """Concatène des FLAC de même format (démultiplexeur concat) vers un WAV temporaire."""
    lines = []
    for p in parts:
        escaped = str(p.resolve()).replace("'", "'\\''")
        lines.append(f"file '{escaped}'")
    list_file.write_text("\n".join(lines) + "\n", "utf-8")
    run_ffmpeg(
        ["-f", "concat", "-safe", "0", "-i", str(list_file), "-c:a", "pcm_s16le", str(dst_wav)]
    )
    return dst_wav


def measure_loudness(src: Path) -> dict[str, str] | None:
    """Première passe de loudnorm (EBU R128) ; None si la mesure est inexploitable."""
    import json
    import re

    cmd = [
        require_ffmpeg(), "-hide_banner", "-nostats", "-i", str(src),
        "-af", f"loudnorm={LOUDNORM}:print_format=json", "-f", "null", "-",
    ]  # fmt: skip
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise FfmpegError(f"ffmpeg a échoué : {res.stderr.strip()[-300:]}")
    blocks = re.findall(r"\{[^{}]*\}", res.stderr, re.S)
    if not blocks:
        return None
    try:
        data = json.loads(blocks[-1])
        out = {k: str(data[k]) for k in (
            "input_i", "input_tp", "input_lra", "input_thresh", "target_offset"
        )}  # fmt: skip
    except (ValueError, KeyError):
        return None
    if any(v in ("-inf", "inf", "nan") for v in out.values()):
        return None  # silence complet : pas de mesure utilisable
    return out


def encode_m4a(
    src_wav: Path,
    dst: Path,
    tags: dict[str, str],
    cover: Path | None = None,
    measured: dict[str, str] | None = None,
) -> Path:
    """Encode en M4A AAC mono 64 kb/s avec loudnorm (2e passe si `measured`) et étiquettes.

    Pas de `use_metadata_tags` : cet indicateur de ffmpeg fait perdre la couverture (atome covr).
    Seules les clés MP4 standard sont donc écrites (title, album, artist, album_artist,
    composer, grouping, genre, date, track, comment).
    """
    filt = f"loudnorm={LOUDNORM}"
    if measured:
        filt += (
            f":measured_I={measured['input_i']}:measured_TP={measured['input_tp']}"
            f":measured_LRA={measured['input_lra']}:measured_thresh={measured['input_thresh']}"
            f":offset={measured['target_offset']}:linear=true"
        )
    args = ["-i", str(src_wav)]
    if cover:
        args += ["-i", str(cover)]
    args += ["-map", "0:a"]
    if cover:
        args += ["-map", "1:v", "-c:v", "copy", "-disposition:v:0", "attached_pic"]
    args += ["-af", filt, "-ar", "44100", "-ac", "1", "-c:a", "aac", "-b:a", "64k"]
    for k, v in tags.items():
        args += ["-metadata", f"{k}={v}"]
    args += ["-movflags", "+faststart", "-f", "ipod", str(dst)]
    run_ffmpeg(args)
    return dst
