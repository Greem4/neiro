"""Догон событий: `GET /v1/events` и `POST /v1/events/ack`.

Устройство определяется по `device_token`, `device_id` в запросе нет — значит
и попросить чужую ленту нечем. Отдельно проверяется горизонт «не старше
сегодня» и то, что курсор не едет назад.
"""

from datetime import datetime, timedelta
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.events import DerivedEvent
from app.main import app
from app.poller import MOSCOW
from app.security import hash_device_token

TODAY = datetime.now(MOSCOW).date().isoformat()
TOMORROW = (datetime.now(MOSCOW).date() + timedelta(days=1)).isoformat()
PAST = "2000-01-01"

DEVICE_TOKEN = "device-token-for-events"
AUTH = {"Authorization": f"Bearer {DEVICE_TOKEN}"}


@pytest.fixture
def client(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("API_KEY", "test-api-key")
    monkeypatch.setenv("ADMIN_API_KEY", "test-admin-key")
    monkeypatch.setenv("TOKEN_ENCRYPTION_KEY", "test-token-key")
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "events.db"))
    monkeypatch.setenv("FCM_CREDENTIALS_PATH", str(tmp_path / "missing-fcm.json"))
    monkeypatch.setenv("YCLIENTS_PARTNER_TOKEN", "partner-token")
    monkeypatch.setenv("YCLIENTS_COMPANY_ID", "520135")
    get_settings.cache_clear()
    with TestClient(app) as test_client:
        yield test_client
    get_settings.cache_clear()


@pytest.fixture
def account(client: TestClient) -> int:
    db = client.app.state.db
    account_id = db.upsert_account(
        company_id=520135,
        staff_id=2008329,
        user_token_enc=client.app.state.secret_box.encrypt("user-token"),
        user_login="+79991234567",
    )
    db.upsert_device(
        account_id=account_id,
        device_id="device-0001",
        token_hash=hash_device_token(DEVICE_TOKEN),
        fcm_token="f" * 32,
        label=None,
        app_version=None,
    )
    return account_id


def _event(record_id: int, date: str = TOMORROW) -> DerivedEvent:
    return DerivedEvent(
        type="NEW_BOOKING",
        client_name="Иванов Ваня",
        date=date,
        time="10:00",
        kind="LESSON",
        record_id=record_id,
    )


def _seed(client: TestClient, account_id: int, *events: DerivedEvent) -> None:
    client.app.state.db.commit_poll_result(account_id, list(events), {})


# --- доступ ------------------------------------------------------------------


def test_events_require_device_token(client: TestClient, account: int) -> None:
    assert client.get("/v1/events").status_code == 401
    assert client.get(
        "/v1/events", headers={"Authorization": "Bearer test-api-key"}
    ).status_code == 401


def test_revoked_token_gets_401(client: TestClient, account: int) -> None:
    client.app.state.db.revoke_device("device-0001")
    assert client.get("/v1/events", headers=AUTH).status_code == 401


def test_reauth_required_still_returns_events(client: TestClient, account: int) -> None:
    """Прокси при `reauth_required` отбивает 409, а лента — нет: события уже
    собраны, и телефону, которому предстоит повторный вход, они нужны."""
    _seed(client, account, _event(1))
    client.app.state.db.set_reauth_required(account, True)

    response = client.get("/v1/events", headers=AUTH)

    assert response.status_code == 200
    assert len(response.json()["events"]) == 1


# --- лента -------------------------------------------------------------------


def test_returns_events_of_own_account_only(client: TestClient, account: int) -> None:
    other = client.app.state.db.upsert_account(
        company_id=520135, staff_id=999, user_token_enc="enc", user_login="+79990000002"
    )
    _seed(client, account, _event(1))
    _seed(client, other, _event(2))

    body = client.get("/v1/events", headers=AUTH).json()

    assert len(body["events"]) == 1
    assert body["events"][0]["staff_id"] == 2008329
    assert body["events"][0]["client_name"] == "Иванов Ваня"
    assert body["has_more"] is False
    assert body["last_event_id"] == body["events"][0]["id"]


def test_past_events_are_not_returned(client: TestClient, account: int) -> None:
    """Горизонт §6.4: уведомление про вчерашнее занятие пользы не приносит."""
    _seed(client, account, _event(1, date=PAST), _event(2, date=TODAY))

    events = client.get("/v1/events", headers=AUTH).json()["events"]

    assert [event["date"] for event in events] == [TODAY]


def test_since_skips_seen_events(client: TestClient, account: int) -> None:
    _seed(client, account, _event(1), _event(2))
    all_events = client.get("/v1/events", headers=AUTH).json()["events"]
    first_id = all_events[0]["id"]

    body = client.get("/v1/events", headers=AUTH, params={"since": first_id}).json()

    assert [event["id"] for event in body["events"]] == [all_events[1]["id"]]


def test_limit_sets_has_more(client: TestClient, account: int) -> None:
    _seed(client, account, _event(1), _event(2), _event(3))

    body = client.get("/v1/events", headers=AUTH, params={"limit": 2}).json()

    assert len(body["events"]) == 2
    assert body["has_more"] is True
    assert body["last_event_id"] == body["events"][-1]["id"]


def test_empty_page_keeps_cursor(client: TestClient, account: int) -> None:
    body = client.get("/v1/events", headers=AUTH, params={"since": 42}).json()

    assert body["events"] == []
    assert body["last_event_id"] == 42
    assert body["has_more"] is False


# --- ack ---------------------------------------------------------------------


def test_ack_moves_cursor(client: TestClient, account: int) -> None:
    response = client.post("/v1/events/ack", headers=AUTH, json={"last_event_id": 17})

    assert response.status_code == 204
    assert client.app.state.db.get_device("device-0001").last_ack_event_id == 17


def test_ack_never_moves_cursor_back(client: TestClient, account: int) -> None:
    """Повторно доехавший ack прошлого догона иначе показал бы события заново."""
    client.post("/v1/events/ack", headers=AUTH, json={"last_event_id": 17})

    client.post("/v1/events/ack", headers=AUTH, json={"last_event_id": 5})

    assert client.app.state.db.get_device("device-0001").last_ack_event_id == 17


def test_ack_requires_device_token(client: TestClient, account: int) -> None:
    response = client.post("/v1/events/ack", json={"last_event_id": 1})

    assert response.status_code == 401
