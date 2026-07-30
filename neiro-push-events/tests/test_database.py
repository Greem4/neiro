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
    account_id = db.upsert_account(1, 10, "pt", "ut")

    event_ids = db.commit_poll_result(account_id, [_event()], {1: _state()})

    assert len(event_ids) == 1
    assert db.list_events_since(account_id, 0)[0].id == event_ids[0]
    assert 1 in db.get_record_states(account_id)


def test_commit_poll_result_rolls_back_events_when_states_write_fails(tmp_path: Path) -> None:
    db = Database(str(tmp_path / "events.db"))
    account_id = db.upsert_account(1, 10, "pt", "ut")

    def _boom(conn, account_id, changed, removed):
        raise RuntimeError("boom")

    db._write_record_states = _boom

    with pytest.raises(RuntimeError):
        db.commit_poll_result(account_id, [_event()], {1: _state()})

    assert db.list_events_since(account_id, 0) == []
    assert db.get_record_states(account_id) == {}


def test_commit_poll_result_skips_write_when_nothing_changed(tmp_path: Path) -> None:
    db = Database(str(tmp_path / "events.db"))
    account_id = db.upsert_account(1, 10, "pt", "ut")
    states = {1: _state()}
    db.commit_poll_result(account_id, [], states)

    def _boom(conn, account_id, changed, removed):
        raise AssertionError("запись не должна открываться без изменений")

    db._write_record_states = _boom

    assert db.commit_poll_result(account_id, [], states, states) == []


def test_commit_poll_result_writes_only_changed_states(tmp_path: Path) -> None:
    db = Database(str(tmp_path / "events.db"))
    account_id = db.upsert_account(1, 10, "pt", "ut")
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
    account_id = db.upsert_account(1, 10, "pt", "ut")
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
    assert db.poll_health_summary()["errors_today"] == 0
