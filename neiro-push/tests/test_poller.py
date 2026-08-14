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

    def __init__(self, result: FcmSendResult | None = None) -> None:
        self.calls: list[dict] = []
        self._result = result or FcmSendResult()

    async def send_events_push(self, *, token: str, events: list[dict], last_event_id: int):
        self.calls.append({"token": token, "events": events, "last_event_id": last_event_id})
        return self._result


def _settings(tmp_path: Path) -> Settings:
    return Settings(
        api_key="test",
        admin_api_key="test-admin",
        database_path=str(tmp_path / "events.db"),
        poll_interval_seconds=10,
        poll_night_interval_seconds=3600,
        quiet_start_hour=23,
        token_encryption_key="test-key",
        yclients_partner_token="pt",
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


def _token_hash(db_path: str, device_id: str) -> str | None:
    conn = sqlite3.connect(db_path)
    try:
        row = conn.execute(
            "SELECT token_hash FROM devices WHERE device_id = ?", (device_id,)
        ).fetchone()
        return row[0] if row else None
    finally:
        conn.close()


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
    account_id = db.upsert_account(1, 10, "ut")
    db.upsert_device(account_id, "dev1", "hash1", "tok1", None, None)

    yclients = FakeYClients([[_record(1, 10)]])
    fcm = FakeFcm()
    service = PollService(settings, db, FakeSecretBox(), yclients, fcm)

    asyncio.run(service.poll_once())

    assert fcm.calls == []
    assert db.get_record_states(account_id)[1].attendance == 0
    # Сидирование не создаёт ни событий, ни пушей — в ленту циклов оно не пишется.
    assert _poll_runs_count(settings.database_path) == 0
    assert service.last_run_at is not None


def test_status_change_sends_push_and_records_delivery(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    db = Database(settings.database_path)
    account_id = db.upsert_account(1, 10, "ut")
    db.upsert_device(account_id, "dev1", "hash1", "tok1", None, None)

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


def test_invalid_fcm_token_clears_token_but_keeps_access(tmp_path: Path) -> None:
    """UNREGISTERED от FCM — это про доставку пуша, а не про доступ.

    Раньше строка устройства удалялась целиком вместе с token_hash, и телефон
    получал 401, то есть полный выход из аккаунта (аудит 14.08.26, K2).
    """
    settings = _settings(tmp_path)
    db = Database(settings.database_path)
    account_id = db.upsert_account(1, 10, "ut")
    db.upsert_device(account_id, "dev1", "hash1", "tok1", None, None)

    yclients = FakeYClients([[_record(1, 10, attendance=0)], [_record(1, 10, attendance=2)]])
    fcm = FakeFcm(result=FcmSendResult(token_invalid=True))
    service = PollService(settings, db, FakeSecretBox(), yclients, fcm)

    asyncio.run(service.poll_once())
    asyncio.run(service.poll_once())

    device = db.get_device("dev1")
    assert device is not None, "строка устройства должна остаться"
    assert device.fcm_token == ""
    assert _token_hash(settings.database_path, "dev1") == "hash1"
    assert [status for _, _, status in _push_deliveries(settings.database_path)] == [
        "token_invalid"
    ]


def test_device_without_fcm_token_is_skipped(tmp_path: Path) -> None:
    """Вход разрешён и без токена Firebase. Слать такому устройству нечего, а
    попытка вернула бы token_invalid и только зря сожгла бы запрос."""
    settings = _settings(tmp_path)
    db = Database(settings.database_path)
    account_id = db.upsert_account(1, 10, "ut")
    db.upsert_device(account_id, "dev-no-fcm", "hash1", "", None, None)

    yclients = FakeYClients([[_record(1, 10, attendance=0)], [_record(1, 10, attendance=2)]])
    fcm = FakeFcm()
    service = PollService(settings, db, FakeSecretBox(), yclients, fcm)

    asyncio.run(service.poll_once())
    asyncio.run(service.poll_once())

    assert fcm.calls == []
    # Событие в журнале осталось: телефон заберёт его догоном.
    assert [e.type for e in db.list_events_since(account_id, 0)] == ["CLIENT_CONFIRMED"]
    assert db.get_device("dev-no-fcm") is not None


def test_one_fetch_per_company_for_multiple_staff(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    db = Database(settings.database_path)
    account_a = db.upsert_account(5, 1, "ut")
    account_b = db.upsert_account(5, 2, "ut")

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
    account_a = db.upsert_account(5, 10, "ut")

    # Аккаунт A уже работает: после первого опроса record_states не пуста.
    # Второй ответ — полный горизонт после регистрации B: та же запись A
    # (давно существующая) не должна дать NEW_BOOKING.
    yclients = FakeYClients([[_record(1, 10)], [_record(1, 10), _record(2, 20)]])
    fcm = FakeFcm()
    service = PollService(settings, db, FakeSecretBox(), yclients, fcm)
    asyncio.run(service.poll_once())
    assert fcm.calls == []

    # Регистрируется аккаунт B — новый специалист, record_states пуста.
    account_b = db.upsert_account(5, 20, "ut")

    asyncio.run(service.poll_once())

    assert fcm.calls == []
    assert db.list_events_since(account_a, 0) == []
    assert 1 in db.get_record_states(account_a)
    assert 2 in db.get_record_states(account_b)


def test_no_changes_produces_zero_events_and_zero_pushes(tmp_path: Path) -> None:
    settings = _settings(tmp_path)
    db = Database(settings.database_path)
    account_id = db.upsert_account(1, 10, "ut")
    db.upsert_device(account_id, "dev1", "hash1", "tok1", None, None)

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
    account_id = db.upsert_account(1, 10, "ut")

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
    db.upsert_account(1, 10, "ut")

    yclients = FakeYClients([RuntimeError("boom"), [_record(1, 10)]])
    fcm = FakeFcm()
    service = PollService(settings, db, FakeSecretBox(), yclients, fcm)

    asyncio.run(service.poll_once())
    assert len(yclients.calls) == 1

    asyncio.run(service.poll_once())
    assert len(yclients.calls) == 1


def test_empty_cycle_is_not_recorded_but_updates_pulse(tmp_path: Path) -> None:
    """Пустой скан в ленту циклов не пишется, но «сервис жив» обновляет.

    При опросе раз в 10 секунд таких циклов набегало больше 8 тысяч в сутки —
    ради них лента и обзавелась фильтром «только важные».
    """
    settings = _settings(tmp_path)
    db = Database(settings.database_path)
    account_id = db.upsert_account(1, 10, "ut")
    db.upsert_device(account_id, "dev1", "hash1", "tok1", None, None)

    record = _record(1, 10)
    yclients = FakeYClients([[record], [record]])
    service = PollService(settings, db, FakeSecretBox(), yclients, FakeFcm())

    asyncio.run(service.poll_once())  # сидирование
    asyncio.run(service.poll_once())  # холостой цикл

    assert _poll_runs_count(settings.database_path) == 0
    assert service.last_run_at is not None
    assert service.last_run_duration_ms is not None
    assert service.last_run_error is None


def test_cycle_with_events_and_failed_cycle_are_recorded(tmp_path: Path) -> None:
    """А вот значимые циклы — события, пуши, ошибка — в ленте остаются."""
    settings = _settings(tmp_path)
    db = Database(settings.database_path)
    account_id = db.upsert_account(1, 10, "ut")
    db.upsert_device(account_id, "dev1", "hash1", "tok1", None, None)

    yclients = FakeYClients(
        [
            [_record(1, 10, attendance=0)],
            [_record(1, 10, attendance=2)],
            RuntimeError("boom"),
        ]
    )
    service = PollService(settings, db, FakeSecretBox(), yclients, FakeFcm())

    asyncio.run(service.poll_once())  # сидирование — пустой, не пишется
    asyncio.run(service.poll_once())  # событие + пуш
    asyncio.run(service.poll_once())  # падение запроса

    runs = db.list_poll_runs_admin(limit=10)
    assert len(runs) == 2
    assert runs[0]["error"] == "boom"
    assert runs[1]["events_created"] == 1 and runs[1]["pushes_sent"] == 1
    assert service.last_run_error == "boom"


def test_backoff_cycles_do_not_flood_the_feed(tmp_path: Path) -> None:
    """Пока держится пауза после сбоя, цикл повторяется каждые 10 секунд.

    В ленте от этого должна остаться одна строка — та, что паузу и назначила,
    а не сотня одинаковых «all accounts backed off».
    """
    settings = _settings(tmp_path)
    db = Database(settings.database_path)
    db.upsert_account(1, 10, "ut")

    yclients = FakeYClients([RuntimeError("boom")])
    service = PollService(settings, db, FakeSecretBox(), yclients, FakeFcm())

    asyncio.run(service.poll_once())  # падение — назначает паузу, пишет строку
    asyncio.run(service.poll_once())  # пауза держится
    asyncio.run(service.poll_once())  # пауза держится

    runs = db.list_poll_runs_admin(limit=10)
    assert len(runs) == 1
    assert runs[0]["error"] == "boom"
    # Но в шапке дашборда пробуксовка видна: цикл был, и он не «ок».
    assert service.last_run_error == "all accounts backed off"


def test_purge_runs_once_an_hour_not_every_cycle(tmp_path: Path) -> None:
    """Чистка старше 90 дней почти всегда не удаляет ничего, но каждый её заход
    берёт write-блокировку SQLite в том же event loop, где идёт опрос."""
    settings = _settings(tmp_path)
    db = Database(settings.database_path)
    db.upsert_account(1, 10, "ut")

    calls: list[int] = []
    db.purge_old_data = lambda *a, **kw: calls.append(1)  # type: ignore[method-assign]

    record = _record(1, 10)
    yclients = FakeYClients([[record], [record], [record]])
    service = PollService(settings, db, FakeSecretBox(), yclients, FakeFcm())

    asyncio.run(service.poll_once())
    asyncio.run(service.poll_once())
    asyncio.run(service.poll_once())

    assert calls == [1]


def test_account_with_reauth_required_is_skipped(tmp_path: Path) -> None:
    """Протух user_token — опрашивать нечем: пароля у сервера нет, и каждый
    запрос таким токеном это гарантированный 401. Ждём повторного входа."""
    settings = _settings(tmp_path)
    db = Database(settings.database_path)
    account_id = db.upsert_account(1, 10, "ut")
    db.upsert_device(account_id, "dev1", "hash1", "tok1", None, None)
    with db.connect() as conn:
        conn.execute("UPDATE accounts SET reauth_required = 1")

    yclients = FakeYClients([[_record(1, 10)]])
    service = PollService(settings, db, FakeSecretBox(), yclients, FakeFcm())

    asyncio.run(service.poll_once())

    assert yclients.calls == []


def test_partner_token_comes_from_settings(tmp_path: Path) -> None:
    """Партнёрский токен один на весь сервис и живёт в .env, а не в колонке
    аккаунта: в базе его больше нет вовсе."""
    settings = _settings(tmp_path)
    db = Database(settings.database_path)
    account_id = db.upsert_account(1, 10, "ut")
    db.upsert_device(account_id, "dev1", "hash1", "tok1", None, None)

    seen: list[str] = []

    class RecordingYClients(FakeYClients):
        async def fetch_company_records(
            self, *, company_id, partner_token, user_token, changed_after
        ):
            seen.append(partner_token)
            return await super().fetch_company_records(
                company_id=company_id,
                partner_token=partner_token,
                user_token=user_token,
                changed_after=changed_after,
            )

    service = PollService(
        settings, db, FakeSecretBox(), RecordingYClients([[_record(1, 10)]]), FakeFcm()
    )
    asyncio.run(service.poll_once())

    assert seen == ["pt"]
    with db.connect() as conn:
        columns = {r["name"] for r in conn.execute("PRAGMA table_info(accounts)")}
    assert "partner_token_enc" not in columns
