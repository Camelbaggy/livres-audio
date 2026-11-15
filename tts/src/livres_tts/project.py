"""Projet de travail produit par `tts prepare` + réglages de synthèse (`build.json`)."""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

BUILD_SCHEMA = 1


class ProjectError(Exception):
    pass


@dataclass
class Silences:
    """Durées en secondes. Entre les phrases d'un segment, c'est le moteur qui décide."""

    segment: float = 0.3  # entre deux segments d'un même paragraphe
    paragraphe: float = 0.6
    scene: float = 1.5  # ligne « * * * »
    titre: float = 1.0  # après le titre lu
    debut: float = 1.0
    fin: float = 2.0


@dataclass
class BuildSettings:
    moteur: str
    voix: str
    serie: str | None = None
    lexiques: str | None = None
    params: dict[str, Any] = field(default_factory=dict)
    silences: Silences = field(default_factory=Silences)

    def to_json(self) -> dict[str, Any]:
        d = asdict(self)
        d["schemaVersion"] = BUILD_SCHEMA
        return d

    @classmethod
    def from_json(cls, d: dict[str, Any]) -> BuildSettings:
        known = Silences.__dataclass_fields__
        sil = Silences(**{k: v for k, v in d.get("silences", {}).items() if k in known})
        return cls(
            moteur=d["moteur"],
            voix=d["voix"],
            serie=d.get("serie"),
            lexiques=d.get("lexiques"),
            params=d.get("params", {}),
            silences=sil,
        )


@dataclass
class ChapterInfo:
    index: int
    titre: str
    titre_lu: str
    path: Path
    stem: str  # « NN - Titre », base du nom de sortie

    def lines(self) -> list[str]:
        text = self.path.read_text("utf-8")
        return [ln.strip() for ln in text.splitlines() if ln.strip()]


@dataclass
class Project:
    dir: Path
    data: dict[str, Any]
    chapters: list[ChapterInfo]

    @property
    def cache_dir(self) -> Path:
        return self.dir / "cache"

    @property
    def out_dir(self) -> Path:
        return self.dir / "sortie"

    @property
    def cover(self) -> Path | None:
        name = self.data.get("couverture")
        p = self.dir / name if name else None
        return p if p and p.exists() else None

    def load_settings(self) -> BuildSettings | None:
        p = self.dir / "build.json"
        if not p.exists():
            return None
        try:
            return BuildSettings.from_json(json.loads(p.read_text("utf-8")))
        except (ValueError, KeyError, TypeError) as exc:
            raise ProjectError(f"{p} illisible : {exc}") from exc

    def save_settings(self, s: BuildSettings) -> None:
        (self.dir / "build.json").write_text(
            json.dumps(s.to_json(), ensure_ascii=False, indent=2) + "\n", "utf-8"
        )


def load_project(path: Path) -> Project:
    path = Path(path)
    pj = path / "projet.json"
    if not pj.exists():
        raise ProjectError(f"{path} : pas un projet (projet.json absent), lancer `tts prepare`")
    try:
        data = json.loads(pj.read_text("utf-8"))
        entries = data["chapitres"]
    except (ValueError, KeyError) as exc:
        raise ProjectError(f"{pj} illisible : {exc}") from exc
    chapters: list[ChapterInfo] = []
    for e in entries:
        txt = path / e["fichier"]
        if not txt.exists():
            raise ProjectError(f"chapitre manquant : {txt}")
        chapters.append(
            ChapterInfo(
                index=int(e["index"]),
                titre=e["titre"],
                titre_lu=e.get("titre_lu") or e["titre"],
                path=txt,
                stem=Path(e["fichier"]).stem,
            )
        )
    return Project(path, data, chapters)
