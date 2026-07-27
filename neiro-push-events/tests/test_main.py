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


def test_admin_events_requires_admin_key(client: TestClient) -> None:
    response = client.get("/v1/admin/events", headers={"Authorization": "Bearer test-api-key"})
    assert response.status_code == 401


def test_admin_events_returns_recent_events(client: TestClient) -> None:
    db = client.app.state.db
    account_id = db.upsert_account(1, 10, "pt", "ut")
    db.commit_poll_result(account_id, [_event(1)], {})

    response = client.get(
        "/v1/admin/events", headers={"Authorization": "Bearer test-admin-key"}
    )

    assert response.status_code == 200
    events = response.json()["events"]
    assert len(events) == 1
    assert events[0]["client_name"] == "Иванов Ваня"
    assert events[0]["targets"] == 0


def test_admin_poll_log_returns_runs(client: TestClient) -> None:
    db = client.app.state.db
    db.record_poll_run(1, "2026-07-26T10:00:00+00:00", 100, 3, 1, 1, None)

    response = client.get(
        "/v1/admin/poll-log", headers={"Authorization": "Bearer test-admin-key"}
    )

    assert response.status_code == 200
    runs = response.json()["poll_runs"]
    assert len(runs) == 1
    assert runs[0]["company_id"] == 1


def test_admin_dashboard_text_requires_admin_key(client: TestClient) -> None:
    response = client.get("/v1/admin/dashboard.txt")
    assert response.status_code == 401


def test_admin_dashboard_text_renders_plain_text(client: TestClient) -> None:
    db = client.app.state.db
    account_id = db.upsert_account(1, 10, "pt", "ut")
    db.commit_poll_result(account_id, [_event(1)], {})

    response = client.get(
        "/v1/admin/dashboard.txt", headers={"Authorization": "Bearer test-admin-key"}
    )

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/plain")
    assert "neiro-push-events" in response.text
    assert "NEW_BOOKING" in response.text


def test_dashboard_without_cookie_shows_login_form(client: TestClient) -> None:
    response = client.get("/dashboard")
    assert response.status_code == 200
    assert "ADMIN_API_KEY" in response.text
    assert "СОБЫТИЯ" not in response.text


def test_dashboard_login_wrong_key_shows_error(client: TestClient) -> None:
    response = client.post("/dashboard/login", data={"key": "wrong"})
    assert response.status_code == 401
    assert "Неверный ключ" in response.text


def test_dashboard_login_sets_cookie_and_unlocks_page(client: TestClient) -> None:
    db = client.app.state.db
    account_id = db.upsert_account(1, 10, "pt", "ut")
    db.commit_poll_result(account_id, [_event(1)], {})

    # Логин отдаёт готовую страницу сразу, без редиректа: абсолютный Location
    # уводил бы браузер мимо префикса /v2 (см. dashboard_login в app/main.py).
    login = client.post(
        "/dashboard/login", data={"key": "test-admin-key"}, follow_redirects=False
    )
    assert login.status_code == 200
    assert "Аккаунты" in login.text
    assert "company=1" in login.text
    assert login.cookies["admin_key"] == "test-admin-key"

    client.cookies.set("admin_key", "test-admin-key")
    response = client.get("/dashboard")

    assert response.status_code == 200
    assert "Аккаунты" in response.text
    assert "company=1" in response.text
    # Отдельного списка событий на странице больше нет: события лежат внутри
    # устройства и подгружаются фрагментом /dashboard/devices/{id}/events.
    assert "Иванов Ваня" not in response.text


def test_dashboard_login_form_posts_to_current_url(client: TestClient) -> None:
    """Форма не должна знать абсолютный путь — публично страница живёт под /v2."""
    response = client.get("/dashboard")
    assert 'action="/dashboard/login"' not in response.text


def test_dashboard_login_accepts_post_on_page_url(client: TestClient) -> None:
    """Форма без action уходит на сам /dashboard — этот POST тоже должен логинить."""
    response = client.post("/dashboard", data={"key": "test-admin-key"})
    assert response.status_code == 200
    assert response.cookies["admin_key"] == "test-admin-key"


def test_dashboard_fragments_require_cookie(client: TestClient) -> None:
    """Фрагменты отдают 401 без куки — страница по нему перезагружается на форму входа."""
    for path in ("/dashboard/status", "/dashboard/poll-runs"):
        assert client.get(path).status_code == 401


def test_dashboard_status_fragment_renders_without_page_chrome(client: TestClient) -> None:
    client.cookies.set("admin_key", "test-admin-key")
    response = client.get("/dashboard/status")

    assert response.status_code == 200
    assert "Аптайм" in response.text
    # Это кусок страницы, а не страница: без <html> его можно класть в innerHTML.
    assert "<html" not in response.text


def test_dashboard_poll_runs_fragment_paginates(client: TestClient) -> None:
    from app.database import utc_now_iso

    db = client.app.state.db
    for _ in range(25):
        db.record_poll_run(1, utc_now_iso(), 5, 0, 0, 0, None)
    client.cookies.set("admin_key", "test-admin-key")

    first = client.get("/dashboard/poll-runs")
    second = client.get("/dashboard/poll-runs", params={"offset": 20})

    assert first.status_code == 200
    assert second.status_code == 200
    assert "1–20 из 25" in first.text
    assert "21–25 из 25" in second.text


def test_dashboard_device_events_fragment_renders_for_known_device(
    client: TestClient,
) -> None:
    _register(client)
    client.cookies.set("admin_key", "test-admin-key")

    response = client.get("/dashboard/devices/device-1/events")

    assert response.status_code == 200
    # Это кусок страницы, а не страница.
    assert "<html" not in response.text


def test_dashboard_device_events_fragment_404_for_unknown_device(client: TestClient) -> None:
    client.cookies.set("admin_key", "test-admin-key")
    response = client.get("/dashboard/devices/no-such-device/events")
    assert response.status_code == 404


def test_uptime_is_written_in_words() -> None:
    """«3д 4ч» читалось хуже всего в первые минуты после рестарта."""
    from app.dashboard import _format_uptime

    assert _format_uptime(2 * 86400 + 4 * 3600) == "2 дня 4 часа"
    assert _format_uptime(5 * 3600 + 12 * 60) == "5 часов 12 минут"
    assert _format_uptime(21 * 60) == "21 минута"
    assert _format_uptime(17) == "17 секунд"
    assert _format_uptime(11 * 3600) == "11 часов 0 минут"


def test_dashboard_time_is_moscow_not_utc() -> None:
    """В базе UTC, на экране МСК (+3) — иначе часы врут на три часа назад."""
    from app.dashboard import _hms

    assert _hms("2026-07-27T08:24:00+00:00") == "11:24:00"
    # naive-строку (формат SQLite datetime('now')) тоже читаем как UTC
    assert _hms("2026-07-27 08:24:00") == "11:24:00"
    assert _hms(None) == "—"


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
