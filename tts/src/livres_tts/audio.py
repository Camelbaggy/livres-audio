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
