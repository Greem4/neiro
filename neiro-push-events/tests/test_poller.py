import asyncio
import sqlite3
from pathlib import Path

from app.config import Settings
from app.database import Database
from app.fcm import FcmSendResult
from app.poller import PollService
from app.yclients import YClientsRecord


class FakeSecretBox:
    def decrypt(self, value: str) -> str:
        return value


class FakeYClients:
    def __init__(self, responses: list) -> None:
        self._responses = list(responses)
        self.calls: list[dict] = []

    async def fetch_company_records(
        self, *, company_id: int, partner_token: str, user_token: str, changed_after
    ) -> list[YClientsRecord]:
        self.calls.append({"company_id": company_id, "changed_after": changed_after})
        response = self._responses.pop(0)
        if isinstance(response, BaseException):
            raise response
        return response


class FakeFcm:
    is_configured = True

    def __init__(self) -> None:
        self.calls: list[dict] = []

    async def send_events_push(self, *, token: str, events: list[dict], last_event_id: int):
        self.calls.append({"token": token, "events": events, "last_event_id": last_event_id})
        return FcmSendResult()


def _settings(tmp_path: Path) -> Settings:
    return Settings(
        api_key="test",
        admin_api_key="test-admin",
        database_path=str(tmp_path / "events.db"),
        poll_interval_seconds=10,
        poll_night_interval_seconds=3600,
        quiet_start_hour=23,
        token_encryption_key="test-key",
    )


def _record(
    record_id: int,
    staff_id: int,
    attendance: int = 0,
    date: str = "2026-07-26",
    time: str = "15:00",
) -> YClientsRecord:
    return YClientsRecord(
        record_id=record_id,
        staff_id=staff_id,
        date=date,
        time=time,
        attendance=attendance,
        deleted=False,
        client_name="Иванов Ваня",
        kind="LESSON",
        last_change_date="2026-07-25 12:00:00",
    )


def _poll_runs_count(db_path: str) -> int:
    conn = sqlite3.connect(db_path)
    try:
        return conn.execute("SELECT COUNT(*) FROM poll_runs").fetchone()[0]
    finally:
        conn.close()


def _account(db: Database, account_id: int):
    return next(a for a in db.list_accounts() if a.id == account_id)


def _push_deliveries(db_path: str) -> list[tuple]:
    conn = sqlite3.connect(db_path)
    try:
        return conn.execute(
            "SELECT event_id, device_id, status FROM push_deliveries"
        ).fetchall()
    finally:
        conn.close()


def test_seeding_produces_no_push(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    db = Database(settings.database_path)
    account_id = db.upsert_account(1, 10, "pt", "ut")
    db.upsert_device(account_id, "dev1", "tok1", None, None)

    yclients = FakeYClients([[_record(1, 10)]])
    fcm = FakeFcm()
    service = PollService(settings, db, FakeSecretBox(), yclients, fcm)

    asyncio.run(service.poll_once())

    assert fcm.calls == []
    assert db.get_record_states(account_id)[1].attendance == 0
    assert _poll_runs_count(settings.database_path) == 1


def test_status_change_sends_push_and_records_delivery(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    db = Database(settings.database_path)
    account_id = db.upsert_account(1, 10, "pt", "ut")
    db.upsert_device(account_id, "dev1", "tok1", None, None)

    yclients = FakeYClients([[_record(1, 10, attendance=0)], [_record(1, 10, attendance=2)]])
    fcm = FakeFcm()
    service = PollService(settings, db, FakeSecretBox(), yclients, fcm)

    asyncio.run(service.poll_once())
    asyncio.run(service.poll_once())

    assert len(fcm.calls) == 1
    events = db.list_events_since(account_id, 0)
    assert [e.type for e in events] == ["CLIENT_CONFIRMED"]
    deliveries = _push_deliveries(settings.database_path)
    assert deliveries == [(events[0].id, "dev1", "sent")]


def test_one_fetch_per_company_for_multiple_staff(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    db = Database(settings.database_path)
    account_a = db.upsert_account(5, 1, "pt", "ut")
    account_b = db.upsert_account(5, 2, "pt", "ut")

    yclients = FakeYClients([[_record(1, 1), _record(2, 2)]])
    fcm = FakeFcm()
    service = PollService(settings, db, FakeSecretBox(), yclients, fcm)

    asyncio.run(service.poll_once())

    assert len(yclients.calls) == 1
    assert 1 in db.get_record_states(account_a)
    assert 2 in db.get_record_states(account_b)


def test_adding_second_staff_seeds_whole_company_without_new_booking_flood(
    tmp_path: Path,
) -> None:
    settings = _settings(tmp_path)
    db = Database(settings.database_path)
    account_a = db.upsert_account(5, 10, "pt", "ut")

    # Аккаунт A уже работает: после первого опроса record_states не пуста.
    # Второй ответ — полный горизонт после регистрации B: та же запись A
    # (давно существующая) не должна дать NEW_BOOKING.
    yclients = FakeYClients([[_record(1, 10)], [_record(1, 10), _record(2, 20)]])
    fcm = FakeFcm()
    service = PollService(settings, db, FakeSecretBox(), yclients, fcm)
    asyncio.run(service.poll_once())
    assert fcm.calls == []

    # Регистрируется аккаунт B — новый специалист, record_states пуста.
    account_b = db.upsert_account(5, 20, "pt", "ut")

    asyncio.run(service.poll_once())

    assert fcm.calls == []
    assert db.list_events_since(account_a, 0) == []
    assert 1 in db.get_record_states(account_a)
    assert 2 in db.get_record_states(account_b)


def test_no_changes_produces_zero_events_and_zero_pushes(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    db = Database(settings.database_path)
    account_id = db.upsert_account(1, 10, "pt", "ut")
    db.upsert_device(account_id, "dev1", "tok1", None, None)

    record = _record(1, 10)
    yclients = FakeYClients([[record], [record]])
    fcm = FakeFcm()
    service = PollService(settings, db, FakeSecretBox(), yclients, fcm)

    asyncio.run(service.poll_once())  # сидирование — базовый снимок
    asyncio.run(service.poll_once())  # холостой опрос — запись не менялась

    assert fcm.calls == []
    assert db.list_events_since(account_id, 0) == []


def test_backoff_grows_with_repeated_failures_and_resets_after_recovery(
    tmp_path: Path,
) -> None:
    settings = _settings(tmp_path)
    db = Database(settings.database_path)
    account_id = db.upsert_account(1, 10, "pt", "ut")

    past = "2020-01-01T00:00:00+00:00"
    yclients = FakeYClients(
        [RuntimeError("boom1"), RuntimeError("boom2"), [_record(1, 10)]]
    )
    fcm = FakeFcm()
    service = PollService(settings, db, FakeSecretBox(), yclients, fcm)

    asyncio.run(service.poll_once())
    account = _account(db, account_id)
    assert account.consecutive_errors == 1
    backoff_after_first = account.backoff_until

    db.update_account_poll_state(account_id, backoff_until=past)
    asyncio.run(service.poll_once())
    account = _account(db, account_id)
    assert account.consecutive_errors == 2
    assert account.backoff_until > backoff_after_first

    db.update_account_poll_state(account_id, backoff_until=past)
    asyncio.run(service.poll_once())
    account = _account(db, account_id)
    assert account.consecutive_errors == 0
    assert account.backoff_until is None


def test_fetch_failure_backs_off_and_skips_next_cycle(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    db = Database(settings.database_path)
    db.upsert_account(1, 10, "pt", "ut")

    yclients = FakeYClients([RuntimeError("boom"), [_record(1, 10)]])
    fcm = FakeFcm()
    service = PollService(settings, db, FakeSecretBox(), yclients, fcm)

    asyncio.run(service.poll_once())
    assert len(yclients.calls) == 1

    asyncio.run(service.poll_once())
    assert len(yclients.calls) == 1
