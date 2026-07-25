from app.events import derive_events, should_seed_baseline
from app.yclients import YClientsRecord


def _record(
    record_id: int = 1,
    date: str = "2026-07-26",
    time: str = "15:00",
    attendance: int = 0,
    deleted: bool = False,
    client_name: str = "Иванов Ваня",
    kind: str = "LESSON",
) -> YClientsRecord:
    return YClientsRecord(
        record_id=record_id,
        staff_id=456,
        date=date,
        time=time,
        attendance=attendance,
        deleted=deleted,
        client_name=client_name,
        kind=kind,
        last_change_date=None,
    )


def test_should_seed_baseline_when_no_previous_state() -> None:
    assert should_seed_baseline({}) is True


def test_should_seed_baseline_false_when_state_exists() -> None:
    _, states = derive_events({}, [_record()])
    assert should_seed_baseline(states) is False


def test_seeding_builds_states_without_events() -> None:
    events, states = derive_events({}, [_record(attendance=0), _record(record_id=2, attendance=-1)])

    assert events == []
    assert set(states.keys()) == {1, 2}
    assert states[1].attendance == 0
    assert states[2].attendance == -1


def test_new_booking_emitted_for_unseen_active_record() -> None:
    existing = _record(record_id=2)
    previous = {2: _state(existing)}

    events, states = derive_events(previous, [existing, _record(record_id=1)])

    assert len(events) == 1
    assert events[0].type == "NEW_BOOKING"
    assert events[0].record_id == 1
    assert 1 in states


def test_new_booking_not_emitted_for_cancelled_unseen_record() -> None:
    previous = {2: _state(_record(record_id=2))}

    events, _ = derive_events(previous, [_record(record_id=1, attendance=-1)])

    assert events == []


def test_new_booking_not_emitted_for_deleted_unseen_record() -> None:
    previous = {2: _state(_record(record_id=2))}

    events, _ = derive_events(previous, [_record(record_id=1, deleted=True)])

    assert events == []


def test_cancelled_emitted_when_attendance_becomes_minus_one() -> None:
    previous = {1: _state(_record(attendance=0))}

    events, states = derive_events(previous, [_record(attendance=-1)])

    assert [e.type for e in events] == ["CANCELLED"]
    assert states[1].attendance == -1


def test_deleted_emitted_when_record_marked_deleted() -> None:
    previous = {1: _state(_record())}

    events, states = derive_events(previous, [_record(deleted=True)])

    assert [e.type for e in events] == ["DELETED"]
    assert states[1].deleted == 1


def test_rescheduled_emitted_with_prev_date_and_time() -> None:
    previous = {1: _state(_record(date="2026-07-26", time="18:00"))}

    events, states = derive_events(previous, [_record(date="2026-07-27", time="12:00")])

    assert len(events) == 1
    event = events[0]
    assert event.type == "RESCHEDULED"
    assert event.date == "2026-07-27"
    assert event.time == "12:00"
    assert event.prev_date == "2026-07-26"
    assert event.prev_time == "18:00"
    assert states[1].date == "2026-07-27"
    assert states[1].time == "12:00"


def test_client_confirmed_emitted_when_attendance_becomes_two() -> None:
    previous = {1: _state(_record(attendance=0))}

    events, _ = derive_events(previous, [_record(attendance=2)])

    assert [e.type for e in events] == ["CLIENT_CONFIRMED"]


def test_client_confirmed_not_repeated_when_already_confirmed() -> None:
    previous = {1: _state(_record(attendance=2))}

    events, _ = derive_events(previous, [_record(attendance=2)])

    assert events == []


def test_client_arrived_emitted_when_attendance_becomes_one() -> None:
    previous = {1: _state(_record(attendance=2))}

    events, _ = derive_events(previous, [_record(attendance=1)])

    assert [e.type for e in events] == ["CLIENT_ARRIVED"]


def test_reschedule_and_status_change_both_fire_independently() -> None:
    previous = {1: _state(_record(date="2026-07-26", time="18:00", attendance=0))}

    events, _ = derive_events(previous, [_record(date="2026-07-27", time="12:00", attendance=2)])

    types = {e.type for e in events}
    assert types == {"RESCHEDULED", "CLIENT_CONFIRMED"}


def test_no_change_produces_no_events() -> None:
    record = _record(attendance=2)
    previous = {1: _state(record)}

    events, states = derive_events(previous, [record])

    assert events == []
    assert states[1] == previous[1]


def _state(record: YClientsRecord):
    _, states = derive_events({}, [record])
    return states[record.record_id]
