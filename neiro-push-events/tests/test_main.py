from datetime import datetime
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.events import DerivedEvent
from app.main import app
from app.poller import MOSCOW

TODAY = datetime.now(MOSCOW).date().isoformat()
PAST = "2000-01-01"


def _event(record_id: int, date: str = TODAY) -> DerivedEvent:
    return DerivedEvent(
        type="NEW_BOOKING",
        client_name="Иванов Ваня",
        date=date,
        time="10:00",
        kind="LESSON",
        record_id=record_id,
    )


@pytest.fixture
def client(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    monkeypatch.setenv("API_KEY", "test-api-key")
    monkeypatch.setenv("ADMIN_API_KEY", "test-admin-key")
    monkeypatch.setenv("TOKEN_ENCRYPTION_KEY", "test-token-key")
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "events.db"))
    monkeypatch.setenv("FCM_CREDENTIALS_PATH", str(tmp_path / "missing-fcm.json"))
    get_settings.cache_clear()
    with TestClient(app) as test_client:
        yield test_client
    get_settings.cache_clear()


def _register(client: TestClient, device_id: str = "device-1") -> dict:
    response = client.post(
        "/v1/devices/register",
        headers={"Authorization": "Bearer test-api-key"},
        json={
            "device_id": device_id,
            "fcm_token": "x" * 32,
            "company_id": 1,
            "staff_id": 10,
            "partner_token": "partner-token",
            "user_token": "user-token",
        },
    )
    assert response.status_code == 200
    return response.json()


def test_register_requires_api_key(client: TestClient) -> None:
    response = client.post(
        "/v1/devices/register",
        json={
            "device_id": "device-1",
            "fcm_token": "x" * 32,
            "company_id": 1,
            "staff_id": 10,
            "partner_token": "partner-token",
            "user_token": "user-token",
        },
    )
    assert response.status_code == 401


def test_register_new_device_starts_cursor_at_journal_end(client: TestClient) -> None:
    db = client.app.state.db
    other_account_id = db.upsert_account(2, 20, "pt", "ut")
    event_ids = db.commit_poll_result(other_account_id, [_event(1), _event(2)], {})

    body = _register(client)

    assert body["last_event_id"] == max(event_ids)
    device = db.get_device("device-1")
    assert device is not None
    assert device.last_ack_event_id == max(event_ids)


def test_register_known_device_keeps_cursor(client: TestClient) -> None:
    db = client.app.state.db
    _register(client)
    db.update_device_ack("device-1", 3)

    body = _register(client)

    assert body["last_event_id"] == 3
    assert db.get_device("device-1").last_ack_event_id == 3


def test_events_requires_api_key(client: TestClient) -> None:
    _register(client)
    response = client.get("/v1/devices/device-1/events", params={"since": 0})
    assert response.status_code == 401


def test_events_unknown_device_is_404(client: TestClient) -> None:
    response = client.get(
        "/v1/devices/unknown/events",
        params={"since": 0},
        headers={"Authorization": "Bearer test-api-key"},
    )
    assert response.status_code == 404


def test_events_filters_past_dates_and_paginates(client: TestClient) -> None:
    body = _register(client)
    db = client.app.state.db
    account_id = db.get_device("device-1").account_id
    db.commit_poll_result(
        account_id,
        [_event(1, date=PAST), _event(2), _event(3), _event(4)],
        {},
    )

    response = client.get(
        "/v1/devices/device-1/events",
        params={"since": body["last_event_id"], "limit": 2},
        headers={"Authorization": "Bearer test-api-key"},
    )
    payload = response.json()

    assert response.status_code == 200
    assert [e["date"] for e in payload["events"]] == [TODAY, TODAY]
    assert [e["staff_id"] for e in payload["events"]] == [10, 10]
    assert payload["has_more"] is True

    tail = client.get(
        "/v1/devices/device-1/events",
        params={"since": payload["last_event_id"], "limit": 2},
        headers={"Authorization": "Bearer test-api-key"},
    )
    tail_payload = tail.json()
    assert len(tail_payload["events"]) == 1
    assert tail_payload["has_more"] is False


def test_ack_updates_cursor(client: TestClient) -> None:
    _register(client)
    db = client.app.state.db
    account_id = db.get_device("device-1").account_id
    event_ids = db.commit_poll_result(account_id, [_event(1)], {})

    response = client.post(
        "/v1/devices/device-1/events/ack",
        json={"last_event_id": event_ids[0]},
        headers={"Authorization": "Bearer test-api-key"},
    )

    assert response.status_code == 204
    assert db.get_device("device-1").last_ack_event_id == event_ids[0]


def test_ack_unknown_device_is_404(client: TestClient) -> None:
    response = client.post(
        "/v1/devices/unknown/events/ack",
        json={"last_event_id": 1},
        headers={"Authorization": "Bearer test-api-key"},
    )
    assert response.status_code == 404


def test_health_requires_admin_key(client: TestClient) -> None:
    response = client.get("/health")
    assert response.status_code == 401

    response = client.get("/health", headers={"Authorization": "Bearer test-api-key"})
    assert response.status_code == 401

    response = client.get("/health", headers={"Authorization": "Bearer test-admin-key"})
    assert response.status_code == 200
    body = response.json()
    assert body["accounts"] == 0
    assert body["devices"] == 0
