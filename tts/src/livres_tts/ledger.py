"""Registre des dépenses : caractères facturables envoyés, par moteur et par mois civil (UTC).

Garde-fou de coût. Principes :
- hors dépôt : `~/.config/livres-tts/registre-depenses.json` (ou `$XDG_CONFIG_HOME`, ou la variable
  `LIVRES_TTS_REGISTRE`, ou le paramètre `path`) ;
- **réservation avant l'appel** (écrite et synchronisée sur disque), confirmation après : une
  interruption ne sous-compte jamais, car l'usage = confirmé + en cours (réservé sans
  confirmation). Un appel en échec reste compté (on ne sait pas s'il a été facturé) ;
- un registre illisible ou incohérent n'est **jamais** traité comme vide : `LedgerCorrupted`.
  Seul un fichier absent est un registre neuf.
"""

from __future__ import annotations

import json
import os
import time
from collections.abc import Callable
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path

SCHEMA_VERSION = 1
LEDGER_ENV = "LIVRES_TTS_REGISTRE"
LOCK_WAIT_S = 5.0
LOCK_STALE_S = 30.0


class LedgerError(Exception):
    pass


class LedgerCorrupted(LedgerError):
    pass


class BudgetRefused(LedgerError):
    """La réservation dépasserait le plafond autorisé pour ce run."""


def default_path() -> Path:
    env = os.environ.get(LEDGER_ENV)
    if env:
        return Path(env)
    base = os.environ.get("XDG_CONFIG_HOME")
    root = Path(base) if base else Path.home() / ".config"
    return root / "livres-tts" / "registre-depenses.json"


def utc_month() -> str:
    return datetime.now(UTC).strftime("%Y-%m")


class Ledger:
    def __init__(
        self,
        path: Path | None = None,
        month: Callable[[], str] = utc_month,
    ) -> None:
        self.path = Path(path) if path else default_path()
        self._month = month

    # -- lecture -----------------------------------------------------------------------------
    def _read(self) -> dict:
        if not self.path.exists():
            return {"schemaVersion": SCHEMA_VERSION, "mois": {}}
        try:
            data = json.loads(self.path.read_text("utf-8"))
        except (OSError, ValueError) as exc:
            raise LedgerCorrupted(self._msg(f"illisible ({exc})")) from exc
        _validate(data, lambda why: LedgerCorrupted(self._msg(why)))
        return data

    def _msg(self, why: str) -> str:
        return (
            f"registre des dépenses {why} : {self.path}. Par prudence aucun appel payant n'est "
            "fait. Corriger ou restaurer le fichier (copie « .bak » à côté), sans le vider."
        )

    def used(self, engine: str, month: str | None = None) -> int:
        """Caractères comptés ce mois : confirmés + réservés non confirmés."""
        data = self._read()
        rec = data["mois"].get(month or self._month(), {}).get(engine, {})
        return int(rec.get("confirme", 0)) + int(rec.get("en_cours", 0))

    # -- écriture ----------------------------------------------------------------------------
    def reserve(self, engine: str, chars: int, max_total: int) -> str:
        """Réserve `chars` si le total du mois reste ≤ `max_total`. Renvoie le mois réservé."""
        if chars < 0:
            raise ValueError("chars < 0")
        with self._locked():
            data = self._read()
            month = self._month()
            rec = data["mois"].setdefault(month, {}).setdefault(
                engine, {"confirme": 0, "en_cours": 0}
            )
            used = int(rec["confirme"]) + int(rec["en_cours"])
            if used + chars > max_total:
                raise BudgetRefused(
                    f"plafond atteint pour {engine} ({month}) : {used} + {chars} > {max_total} "
                    "caractères autorisés"
                )
            rec["en_cours"] = int(rec["en_cours"]) + chars
            self._write(data)
            return month

    def confirm(self, engine: str, chars: int, month: str) -> None:
        with self._locked():
            data = self._read()
            rec = data["mois"].get(month, {}).get(engine)
            if rec is None:
                raise LedgerCorrupted(self._msg("sans la réservation attendue"))
            moved = min(chars, int(rec["en_cours"]))
            rec["en_cours"] = int(rec["en_cours"]) - moved
            rec["confirme"] = int(rec["confirme"]) + moved
            self._write(data)

    def _write(self, data: dict) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if self.path.exists():
            self.path.with_suffix(".bak").write_bytes(self.path.read_bytes())
        tmp = self.path.with_suffix(".tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, self.path)

    @contextmanager
    def _locked(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        lock = self.path.with_suffix(".lock")
        deadline = time.monotonic() + LOCK_WAIT_S
        while True:
            try:
                fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
                os.close(fd)
                break
            except FileExistsError:
                try:
                    if time.time() - lock.stat().st_mtime > LOCK_STALE_S:
                        lock.unlink(missing_ok=True)
                        continue
                except OSError:
                    pass
                if time.monotonic() > deadline:
                    raise LedgerError(
                        f"registre verrouillé par un autre processus : {lock}"
                    ) from None
                time.sleep(0.05)
        try:
            yield
        finally:
            lock.unlink(missing_ok=True)


def _validate(data: object, err: Callable[[str], Exception]) -> None:
    if not isinstance(data, dict) or data.get("schemaVersion") != SCHEMA_VERSION:
        raise err("de format inconnu")
    mois = data.get("mois")
    if not isinstance(mois, dict):
        raise err("sans table « mois »")
    for month, engines in mois.items():
        if not isinstance(engines, dict) or len(str(month)) != 7:
            raise err("incohérent (mois)")
        for rec in engines.values():
            if not isinstance(rec, dict):
                raise err("incohérent (moteur)")
            for k in ("confirme", "en_cours"):
                v = rec.get(k)
                if isinstance(v, bool) or not isinstance(v, int) or v < 0:
                    raise err(f"incohérent ({k})")
