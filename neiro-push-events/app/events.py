from __future__ import annotations

from dataclasses import dataclass

from app.database import RecordState
from app.yclients import YClientsRecord


@dataclass(frozen=True)
class DerivedEvent:
    type: str
    client_name: str
    date: str
    time: str
    kind: str
    record_id: int
    prev_date: str | None = None
    prev_time: str | None = None


def should_seed_baseline(previous: dict[int, RecordState]) -> bool:
    return not previous


def derive_events(
    previous: dict[int, RecordState],
    records: list[YClientsRecord],
) -> tuple[list[DerivedEvent], dict[int, RecordState]]:
    """Дифф состояний по правилам §6.3 плана.

    Чистая функция — без БД и сети. `previous` уже отражает последнее
    сохранённое состояние аккаунта; при сидировании (previous пуст) события
    не генерируются, только строится базовый снимок.
    """
    seeding = should_seed_baseline(previous)
    new_states = dict(previous)
    events: list[DerivedEvent] = []

    for record in records:
        old = previous.get(record.record_id)

        if old is None:
            if not seeding and not record.deleted and record.attendance != -1:
                events.append(
                    DerivedEvent(
                        type="NEW_BOOKING",
                        client_name=record.client_name,
                        date=record.date,
                        time=record.time,
                        kind=record.kind,
                        record_id=record.record_id,
                    )
                )
            new_states[record.record_id] = _state_from_record(record)
            continue

        # Удаление терминально: записи больше нет, остальное по ней не считаем
        # (§3.3 stage1-4-review — иначе одно действие даёт два уведомления).
        if record.deleted:
            if not old.deleted:
                events.append(
                    DerivedEvent(
                        type="DELETED",
                        client_name=record.client_name,
                        date=record.date,
                        time=record.time,
                        kind=record.kind,
                        record_id=record.record_id,
                    )
                )
            new_states[record.record_id] = _state_from_record(record)
            continue

        # Возврат: была удалена или отменена, снова стала активной — это
        # NEW_BOOKING, как в приложении (SessionChangeDetector:45,71).
        if old.deleted or (old.attendance == -1 and record.attendance != -1):
            events.append(
                DerivedEvent(
                    type="NEW_BOOKING",
                    client_name=record.client_name,
                    date=record.date,
                    time=record.time,
                    kind=record.kind,
                    record_id=record.record_id,
                )
            )
            new_states[record.record_id] = _state_from_record(record)
            continue

        # Отмена тоже обрывает разбор (SessionChangeDetector:61-66).
        if old.attendance != -1 and record.attendance == -1:
            events.append(
                DerivedEvent(
                    type="CANCELLED",
                    client_name=record.client_name,
                    date=record.date,
                    time=record.time,
                    kind=record.kind,
                    record_id=record.record_id,
                )
            )
            new_states[record.record_id] = _state_from_record(record)
            continue

        # Дальше — обычные изменения активной записи; перенос и смена
        # статуса за один цикл дают два разных события (§6.3 плана).
        if old.date != record.date or old.time != record.time:
            events.append(
                DerivedEvent(
                    type="RESCHEDULED",
                    client_name=record.client_name,
                    date=record.date,
                    time=record.time,
                    kind=record.kind,
                    record_id=record.record_id,
                    prev_date=old.date,
                    prev_time=old.time,
                )
            )

        if record.attendance == 2 and old.attendance != 2:
            events.append(
                DerivedEvent(
                    type="CLIENT_CONFIRMED",
                    client_name=record.client_name,
                    date=record.date,
                    time=record.time,
                    kind=record.kind,
                    record_id=record.record_id,
                )
            )

        if record.attendance == 1 and old.attendance != 1:
            events.append(
                DerivedEvent(
                    type="CLIENT_ARRIVED",
                    client_name=record.client_name,
                    date=record.date,
                    time=record.time,
                    kind=record.kind,
                    record_id=record.record_id,
                )
            )

        new_states[record.record_id] = _state_from_record(record)

    return events, new_states


def _state_from_record(record: YClientsRecord) -> RecordState:
    return RecordState(
        record_id=record.record_id,
        date=record.date,
        time=record.time,
        attendance=record.attendance,
        deleted=int(record.deleted),
        client_name=record.client_name,
        kind=record.kind,
    )
