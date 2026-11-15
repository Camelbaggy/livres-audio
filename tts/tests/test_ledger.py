from __future__ import annotations

import json

import pytest

from livres_tts.ledger import BudgetRefused, Ledger, LedgerCorrupted


def make(tmp_path, month="2026-11"):
    state = {"m": month}
    return Ledger(tmp_path / "reg" / "registre.json", month=lambda: state["m"]), state


def test_new_ledger_is_empty_and_reserve_confirm(tmp_path):
    led, _ = make(tmp_path)
    assert led.used("google") == 0
    m = led.reserve("google", 100, 1000)
    assert led.used("google") == 100  # réservé = compté avant confirmation
    data = json.loads(led.path.read_text("utf-8"))
    assert data["mois"]["2026-11"]["google"] == {"confirme": 0, "en_cours": 100}
    led.confirm("google", 100, m)
    assert json.loads(led.path.read_text("utf-8"))["mois"]["2026-11"]["google"] == {
        "confirme": 100,
        "en_cours": 0,
    }
    assert led.used("google") == 100


def test_unconfirmed_reservation_is_never_forgotten(tmp_path):
    led, _ = make(tmp_path)
    led.reserve("google", 300, 1000)  # interruption : pas de confirm
    led2 = Ledger(led.path, month=lambda: "2026-11")
    assert led2.used("google") == 300


def test_reserve_refused_above_max_and_not_written(tmp_path):
    led, _ = make(tmp_path)
    led.reserve("google", 900, 1000)
    with pytest.raises(BudgetRefused):
        led.reserve("google", 101, 1000)
    assert led.used("google") == 900
    led.reserve("google", 100, 1000)  # pile au plafond


def test_month_change_resets_usage_but_keeps_history(tmp_path):
    led, state = make(tmp_path)
    led.reserve("google", 500, 1000)
    state["m"] = "2026-12"
    assert led.used("google") == 0
    led.reserve("google", 10, 1000)
    assert led.used("google", "2026-11") == 500


@pytest.mark.parametrize(
    "content",
    [
        "",
        "{pas du json",
        "[]",
        '{"schemaVersion": 99, "mois": {}}',
        '{"schemaVersion": 1}',
        '{"schemaVersion": 1, "mois": {"2026-11": {"google": {"confirme": -5, "en_cours": 0}}}}',
        '{"schemaVersion": 1, "mois": {"2026-11": {"google": {"confirme": "x", "en_cours": 0}}}}',
        '{"schemaVersion": 1, "mois": {"2026-11": {"google": {"confirme": 0}}}}',
    ],
)
def test_corrupted_ledger_is_refused_never_empty(tmp_path, content):
    led, _ = make(tmp_path)
    led.path.parent.mkdir(parents=True)
    led.path.write_text(content, "utf-8")
    with pytest.raises(LedgerCorrupted):
        led.used("google")
    with pytest.raises(LedgerCorrupted):
        led.reserve("google", 1, 10**9)
    assert led.path.read_text("utf-8") == content  # jamais réécrit


def test_backup_kept(tmp_path):
    led, _ = make(tmp_path)
    led.reserve("google", 1, 10)
    led.reserve("google", 1, 10)
    assert led.path.with_suffix(".bak").exists()
