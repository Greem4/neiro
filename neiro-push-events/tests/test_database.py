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

    def _boom(conn, account_id, states):
        raise RuntimeError("boom")

    db._replace_record_states = _boom

    with pytest.raises(RuntimeError):
        db.commit_poll_result(account_id, [_event()], {1: _state()})

    assert db.list_events_since(account_id, 0) == []
    assert db.get_record_states(account_id) == {}
