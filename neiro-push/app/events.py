from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from app.database import RecordState
from app.yclients import YClientsRecord, parse_yclients_timestamp


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
    created_since: datetime | None = None,
) -> tuple[list[DerivedEvent], dict[int, RecordState]]:
    """Дифф состояний по правилам §6.3 плана.

    Чистая функция — без БД и сети. `previous` уже отражает последнее
    сохранённое состояние аккаунта; при сидировании (previous пуст) события
    не генерируются, только строится базовый снимок.

    `created_since` — курсор прошлого опроса. Запись, которой нет в снимке, но
    создана она раньше курсора, — не новая: это давняя запись (постоянный
    ребёнок), которую снимок не застал. Снимок сидируется один раз на окно
    `horizon_days`, а дальше узнаёт только об изменённых записях, поэтому
    записи серий, въехавшие в окно позже, впервые приходят в момент своего
    изменения — обычно подтверждения. Раньше это уходило как NEW_BOOKING.
    """
    seeding = should_seed_baseline(previous)
    new_states = dict(previous)
    events: list[DerivedEvent] = []

    for record in records:
        old = previous.get(record.record_id)

        if old is None:
            if not seeding:
                event_type = (
                    _new_record_event_type(record)
                    if _is_freshly_created(record, created_since)
                    else _unseen_record_event_type(record)
                )
                if event_type is not None:
                    events.append(
                        DerivedEvent(
                            type=event_type,
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


def _is_freshly_created(record: YClientsRecord, created_since: datetime | None) -> bool:
    """Создана ли запись после прошлого опроса.

    Нечего сравнить — нет курсора или даты создания — считаем новой: так было
    до появления этой проверки, и лишнее «новая запись» лучше пропущенной.
    """
    if created_since is None:
        return True
    created = parse_yclients_timestamp(record.create_date)
    if created is None:
        return True
    return created >= created_since


def _new_record_event_type(record: YClientsRecord) -> str | None:
    if record.deleted or record.attendance == -1:
        return None
    return "NEW_BOOKING"


def _unseen_record_event_type(record: YClientsRecord) -> str | None:
    """Давняя запись, которую снимок не застал: прежнего состояния нет, поэтому
    о ней говорим по текущему статусу. «Ожидает» — молчим: что именно
    изменилось, сравнить не с чем."""
    if record.deleted:
        return "DELETED"
    if record.attendance == -1:
        return "CANCELLED"
    if record.attendance == 2:
        return "CLIENT_CONFIRMED"
    if record.attendance == 1:
        return "CLIENT_ARRIVED"
    return None


def merge_states(
    previous: dict[int, RecordState],
    records: list[YClientsRecord],
) -> dict[int, RecordState]:
    """Сидирование: состояния обновляются, события не генерируются (§7 Этап 5 п.3)."""
    states = dict(previous)
    for record in records:
        states[record.record_id] = _state_from_record(record)
    return states


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
