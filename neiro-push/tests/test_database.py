from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from app.database import Database, RecordState
from app.events import DerivedEvent


def _event(record_id: int = 1) -> DerivedEvent:
    return DerivedEvent(
        type="NEW_BOOKING",
        client_name="Иванов Ваня",
        date="2026-07-26",
        time="10:00",
        kind="LESSON",
        record_id=record_id,
    )


def _state(record_id: int = 1) -> RecordState:
    return RecordState(
        record_id=record_id,
        date="2026-07-26",
        time="10:00",
        attendance=0,
        deleted=0,
        client_name="Иванов Ваня",
        kind="LESSON",
    )


def test_commit_poll_result_writes_events_and_states_together(tmp_path: Path) -> None:
    db = Database(str(tmp_path / "events.db"))
    account_id = db.upsert_account(1, 10, "ut")

    event_ids = db.commit_poll_result(account_id, [_event()], {1: _state()})

    assert len(event_ids) == 1
    assert db.list_events_since(account_id, 0)[0].id == event_ids[0]
    assert 1 in db.get_record_states(account_id)


def test_commit_poll_result_rolls_back_events_when_states_write_fails(tmp_path: Path) -> None:
    db = Database(str(tmp_path / "events.db"))
    account_id = db.upsert_account(1, 10, "ut")

    def _boom(conn, account_id, changed, removed):
        raise RuntimeError("boom")

    db._write_record_states = _boom

    with pytest.raises(RuntimeError):
        db.commit_poll_result(account_id, [_event()], {1: _state()})

    assert db.list_events_since(account_id, 0) == []
    assert db.get_record_states(account_id) == {}


def test_commit_poll_result_skips_write_when_nothing_changed(tmp_path: Path) -> None:
    db = Database(str(tmp_path / "events.db"))
    account_id = db.upsert_account(1, 10, "ut")
    states = {1: _state()}
    db.commit_poll_result(account_id, [], states)

    def _boom(conn, account_id, changed, removed):
        raise AssertionError("запись не должна открываться без изменений")

    db._write_record_states = _boom

    assert db.commit_poll_result(account_id, [], states, states) == []


def test_commit_poll_result_writes_only_changed_states(tmp_path: Path) -> None:
    db = Database(str(tmp_path / "events.db"))
    account_id = db.upsert_account(1, 10, "ut")
    previous = {1: _state(1), 2: _state(2)}
    db.commit_poll_result(account_id, [], previous)

    moved = RecordState(
        record_id=1,
        date="2026-07-27",
        time="12:00",
        attendance=0,
        deleted=0,
        client_name="Иванов Ваня",
        kind="LESSON",
    )
    db.commit_poll_result(account_id, [], {1: moved}, previous)

    states = db.get_record_states(account_id)
    assert states == {1: moved}


def test_day_window_counts_exactly_last_24_hours(tmp_path: Path) -> None:
    """Событие 25-часовой давности за сутки не считается — даже если это ещё
    вчерашняя календарная дата."""
    db = Database(str(tmp_path / "events.db"))
    account_id = db.upsert_account(1, 10, "ut")
    db.commit_poll_result(account_id, [_event()], {})

    old = (datetime.now(timezone.utc) - timedelta(hours=25)).replace(microsecond=0).isoformat()
    with db.connect() as conn:
        conn.execute("UPDATE events SET created_at = ?", (old,))
        conn.execute(
            """
            INSERT INTO poll_runs (
                company_id, started_at, duration_ms, records_fetched,
                events_created, pushes_sent, error
            ) VALUES (1, ?, 10, 0, 0, 0, 'boom')
            """,
            (old,),
        )

    assert db.stats()["events_today"] == 0
    assert db.poll_errors_today() == 0


def test_deliveries_by_device_go_through_index(tmp_path: Path) -> None:
    db = Database(str(tmp_path / "events.db"))
    with db.connect() as conn:
        plan = conn.execute(
            "EXPLAIN QUERY PLAN "
            "SELECT id FROM push_deliveries WHERE device_id = 'dev1' ORDER BY id DESC"
        ).fetchall()

    assert any("idx_deliveries_device" in row["detail"] for row in plan)


def test_repeat_login_replaces_token_and_keeps_cursor(tmp_path: Path) -> None:
    """Повторный вход с того же устройства: старый device_token умирает, отзыв
    снимается, а курсор событий переживает — иначе телефон потерял бы всё, что
    накопилось за время без связи."""
    db = Database(str(tmp_path / "events.db"))
    account_id = db.upsert_account(1, 10, "ut")
    db.upsert_device(account_id, "dev1", "hash-old", "fcm1", None, None)
    db.update_device_ack("dev1", 42)
    with db.connect() as conn:
        conn.execute("UPDATE devices SET revoked_at = '2026-08-01T00:00:00+00:00'")

    db.upsert_device(account_id, "dev1", "hash-new", "fcm2", None, None)

    with db.connect() as conn:
        row = conn.execute(
            "SELECT token_hash, revoked_at, last_ack_event_id FROM devices WHERE device_id = 'dev1'"
        ).fetchone()
    assert row["token_hash"] == "hash-new"
    assert row["revoked_at"] is None
    assert row["last_ack_event_id"] == 42


def test_successful_login_clears_reauth_required(tmp_path: Path) -> None:
    db = Database(str(tmp_path / "events.db"))
    account_id = db.upsert_account(1, 10, "ut")
    with db.connect() as conn:
        conn.execute("UPDATE accounts SET reauth_required = 1")
    assert db.get_account(account_id).reauth_required is True

    db.upsert_account(1, 10, "ut-fresh")

    assert db.get_account(account_id).reauth_required is False


def test_revoked_device_gets_no_pushes(tmp_path: Path) -> None:
    db = Database(str(tmp_path / "events.db"))
    account_id = db.upsert_account(1, 10, "ut")
    db.upsert_device(account_id, "dev1", "hash1", "fcm1", None, None)
    db.upsert_device(account_id, "dev2", "hash2", "fcm2", None, None)
    with db.connect() as conn:
        conn.execute(
            "UPDATE devices SET revoked_at = '2026-08-01T00:00:00+00:00' WHERE device_id = 'dev1'"
        )

    device_ids = [d.device_id for d in db.list_devices_for_account(account_id)]

    assert device_ids == ["dev2"]
