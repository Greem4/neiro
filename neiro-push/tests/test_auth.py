import logging
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.main import app
from app.security import hash_device_token
from app.yclients import YClientsAuthError, YClientsUpstreamError, match_staff_id

PASSWORD = "очень-секретный-пароль-42"
APP_KEY = {"Authorization": "Bearer test-api-key"}


class FakeYClients:
    """Подменённый YClients: настоящий в тестах не трогаем."""

    def __init__(self) -> None:
        self.auth_error: Exception | None = None
        self.staff_error: Exception | None = None
        self.user_name = "Зеленкина Светлана Васильевна"
        self.staff = [
            {"id": 111, "name": "Иванова Мария", "fired": 0},
            {"id": 2008329, "name": "Светлана Зеленкина", "fired": 0},
        ]
        self.seen_passwords: list[str] = []

    async def login(self, login: str, password: str) -> dict:
        self.seen_passwords.append(password)
        if self.auth_error:
            raise self.auth_error
        return {
            "id": 7,
            "user_token": "user-token-from-yclients",
            "name": self.user_name,
            "avatar": "//cdn.yclients.com/a.png",
        }

    async def fetch_staff(self, company_id: int) -> list[dict]:
        if self.staff_error:
            raise self.staff_error
        return self.staff


@pytest.fixture
def yclients() -> FakeYClients:
    return FakeYClients()


@pytest.fixture
def client(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, yclients: FakeYClients):
    monkeypatch.setenv("API_KEY", "test-api-key")
    monkeypatch.setenv("ADMIN_API_KEY", "test-admin-key")
    monkeypatch.setenv("TOKEN_ENCRYPTION_KEY", "test-token-key")
    monkeypatch.setenv("DATABASE_PATH", str(tmp_path / "push.db"))
    monkeypatch.setenv("FCM_CREDENTIALS_PATH", str(tmp_path / "missing-fcm.json"))
    monkeypatch.setenv("YCLIENTS_PARTNER_TOKEN", "partner-token")
    monkeypatch.setenv("YCLIENTS_COMPANY_ID", "520135")
    get_settings.cache_clear()
    with TestClient(app) as test_client:
        test_client.app.state.yclients = yclients
        yield test_client
    get_settings.cache_clear()


def _login(client: TestClient, device_id: str = "device-0001") -> dict:
    response = client.post(
        "/v1/auth/login",
        headers=APP_KEY,
        json={
            "login": "+79991234567",
            "password": PASSWORD,
            "device_id": device_id,
            "fcm_token": "f" * 32,
            "label": "Pixel 7",
            "app_version": "0.1.0",
        },
    )
    assert response.status_code == 200, response.text
    return response.json()


def _auth(device_token: str) -> dict:
    return {"Authorization": f"Bearer {device_token}"}


# --- подбор сотрудника -------------------------------------------------------


def test_staff_matched_across_name_formats() -> None:
    """`/auth` отдаёт с отчеством, `/book_staff` — без. Прошлая версия требовала
    совпадения всех токенов и на лишнем «Васильевна» матч теряла."""
    staff = [{"id": 5, "name": "Светлана Зеленкина", "fired": 0}]
    assert match_staff_id("Зеленкина Светлана Васильевна", staff) == 5


def test_single_token_name_lowers_the_threshold() -> None:
    assert match_staff_id("Светлана", [{"id": 5, "name": "Светлана", "fired": 0}]) == 5


def test_one_common_token_is_not_enough_for_full_names() -> None:
    """Одного «Светлана» мало: в филиале бывают тёзки."""
    staff = [{"id": 5, "name": "Светлана Петрова", "fired": 0}]
    assert match_staff_id("Зеленкина Светлана Васильевна", staff) is None


def test_active_staff_wins_over_fired_namesake() -> None:
    staff = [
        {"id": 1, "name": "Светлана Зеленкина", "fired": 1},
        {"id": 2, "name": "Светлана Зеленкина", "fired": 0},
    ]
    assert match_staff_id("Зеленкина Светлана Васильевна", staff) == 2


def test_yo_is_the_same_letter() -> None:
    assert match_staff_id("Алёна Тёркина", [{"id": 9, "name": "Алена Теркина"}]) == 9


# --- вход --------------------------------------------------------------------


def test_login_issues_token_and_stores_only_its_hash(client: TestClient) -> None:
    body = _login(client)

    device_token = body["device_token"]
    assert body["account"]["staff_id"] == 2008329
    assert body["account"]["company_id"] == 520135
    assert body["account"]["avatar_url"] == "https://cdn.yclients.com/a.png"

    db = client.app.state.db
    with db.connect() as conn:
        row = conn.execute("SELECT token_hash, fcm_token FROM devices").fetchone()
        account = conn.execute("SELECT user_token_enc, user_login FROM accounts").fetchone()

    assert row["token_hash"] == hash_device_token(device_token)
    # Сам токен в базе не лежит ни в каком виде.
    assert device_token not in row["token_hash"]
    assert account["user_login"] == "+79991234567"
    # user_token зашифрован, а не сложен как есть.
    assert account["user_token_enc"] != "user-token-from-yclients"
    assert client.app.state.secret_box.decrypt(account["user_token_enc"]) == (
        "user-token-from-yclients"
    )


def test_login_requires_app_key(client: TestClient) -> None:
    response = client.post(
        "/v1/auth/login",
        json={
            "login": "+79991234567",
            "password": PASSWORD,
            "device_id": "device-0001",
            "fcm_token": "f" * 32,
        },
    )
    assert response.status_code == 401


def test_wrong_password_is_401(client: TestClient, yclients: FakeYClients) -> None:
    yclients.auth_error = YClientsAuthError("nope")
    response = client.post(
        "/v1/auth/login",
        headers=APP_KEY,
        json={
            "login": "+79991234567",
            "password": "не тот",
            "device_id": "device-0001",
            "fcm_token": "f" * 32,
        },
    )
    assert response.status_code == 401
    assert response.json()["detail"] == "invalid_credentials"


def test_unmatched_name_is_409(client: TestClient, yclients: FakeYClients) -> None:
    yclients.user_name = "Совсем Другой Человек"
    response = client.post(
        "/v1/auth/login",
        headers=APP_KEY,
        json={
            "login": "+79991234567",
            "password": PASSWORD,
            "device_id": "device-0001",
            "fcm_token": "f" * 32,
        },
    )
    assert response.status_code == 409
    assert response.json()["detail"] == "staff_not_found"
    # Аккаунт не заводится: входа не было.
    with client.app.state.db.connect() as conn:
        assert conn.execute("SELECT COUNT(*) AS c FROM accounts").fetchone()["c"] == 0


def test_unavailable_yclients_is_502(client: TestClient, yclients: FakeYClients) -> None:
    yclients.auth_error = YClientsUpstreamError("boom")
    response = client.post(
        "/v1/auth/login",
        headers=APP_KEY,
        json={
            "login": "+79991234567",
            "password": PASSWORD,
            "device_id": "device-0001",
            "fcm_token": "f" * 32,
        },
    )
    assert response.status_code == 502
    assert response.json()["detail"] == "upstream_error"


def test_relogin_kills_old_token_and_keeps_cursor(client: TestClient) -> None:
    first = _login(client)
    db = client.app.state.db
    db.update_device_ack("device-0001", 4821)

    second = _login(client)

    assert second["device_token"] != first["device_token"]
    # Старый токен мёртв сразу.
    assert client.get("/v1/session", headers=_auth(first["device_token"])).status_code == 401
    assert client.get("/v1/session", headers=_auth(second["device_token"])).status_code == 200
    # Курсор событий цел — за время без связи ничего не потеряно.
    assert second["last_event_id"] == 4821
    with db.connect() as conn:
        assert conn.execute("SELECT COUNT(*) AS c FROM devices").fetchone()["c"] == 1


def _token_hash(client: TestClient, device_id: str) -> str:
    with client.app.state.db.connect() as conn:
        return conn.execute(
            "SELECT token_hash FROM devices WHERE device_id = ?", (device_id,)
        ).fetchone()["token_hash"]


def test_login_by_another_account_does_not_steal_the_device(
    client: TestClient, yclients: FakeYClients
) -> None:
    """device_id приходит из тела запроса и был ключом upsert'а: чужой вход
    переписывал строку, убивая token_hash хозяина телефона и уводя на себя его
    пуши (аудит 14.08.26, K6)."""
    first = _login(client)
    hash_before = _token_hash(client, "device-0001")

    # Другой сотрудник того же филиала — свои учётные данные, чужой device_id.
    yclients.user_name = "Иванова Мария"
    response = client.post(
        "/v1/auth/login",
        headers=APP_KEY,
        json={
            "login": "+79990000000",
            "password": PASSWORD,
            "device_id": "device-0001",
            "fcm_token": "g" * 32,
        },
    )

    assert response.status_code == 409
    assert response.json()["detail"] == "device_taken"
    assert _token_hash(client, "device-0001") == hash_before
    # Телефон хозяина продолжает работать.
    assert client.get("/v1/session", headers=_auth(first["device_token"])).status_code == 200


def test_released_device_can_be_claimed_by_another_account(
    client: TestClient, yclients: FakeYClients
) -> None:
    """«Сменить аккаунт» на своём же телефоне — штатный сценарий: выход
    отзывает устройство, и привязка к прежнему аккаунту больше не держит."""
    first = _login(client)
    assert client.post("/v1/auth/logout", headers=_auth(first["device_token"])).status_code == 204

    yclients.user_name = "Иванова Мария"
    second = _login(client)

    assert second["device_token"] != first["device_token"]
    assert client.get("/v1/session", headers=_auth(second["device_token"])).status_code == 200


def test_password_never_reaches_logs(
    client: TestClient, yclients: FakeYClients, caplog: pytest.LogCaptureFixture
) -> None:
    """Лог сервиса читают глазами и складывают в бэкапы — пароля в нём быть не
    должно ни при удачном входе, ни при отказе, ни при падении YClients."""
    with caplog.at_level(logging.DEBUG):
        _login(client)
        yclients.auth_error = YClientsAuthError("nope")
        client.post(
            "/v1/auth/login",
            headers=APP_KEY,
            json={
                "login": "+79991234567",
                "password": PASSWORD,
                "device_id": "device-0002",
                "fcm_token": "f" * 32,
            },
        )
        yclients.auth_error = YClientsUpstreamError("upstream is down")
        client.post(
            "/v1/auth/login",
            headers=APP_KEY,
            json={
                "login": "+79991234567",
                "password": PASSWORD,
                "device_id": "device-0003",
                "fcm_token": "f" * 32,
            },
        )

    # Пароль до YClients дошёл — значит проверяем именно тот, что был отправлен.
    assert PASSWORD in yclients.seen_passwords
    assert PASSWORD not in caplog.text


def test_password_is_not_in_error_body(client: TestClient, yclients: FakeYClients) -> None:
    yclients.auth_error = YClientsAuthError("nope")
    response = client.post(
        "/v1/auth/login",
        headers=APP_KEY,
        json={
            "login": "+79991234567",
            "password": PASSWORD,
            "device_id": "device-0001",
            "fcm_token": "f" * 32,
        },
    )
    assert PASSWORD not in response.text


# --- сессия, выход, FCM ------------------------------------------------------


def test_session_returns_account_without_touching_yclients(client: TestClient) -> None:
    token = _login(client)["device_token"]

    response = client.get("/v1/session", headers=_auth(token))

    assert response.status_code == 200
    body = response.json()
    assert body["account"]["staff_id"] == 2008329
    assert body["account"]["user_name"] == "Зеленкина Светлана Васильевна"
    assert body["reauth_required"] is False


def test_session_reports_reauth_required_instead_of_refusing(client: TestClient) -> None:
    """Именно тогда, когда флаг взведён, приложению и нужен ответ — иначе оно не
    узнает, что пора просить пароль."""
    token = _login(client)["device_token"]
    db = client.app.state.db
    db.set_reauth_required(1, True)

    response = client.get("/v1/session", headers=_auth(token))

    assert response.status_code == 200
    assert response.json()["reauth_required"] is True


def test_unknown_token_is_401(client: TestClient) -> None:
    assert client.get("/v1/session", headers=_auth("made-up-token")).status_code == 401
    assert client.get("/v1/session").status_code == 401


def test_logout_revokes_token_but_keeps_account(client: TestClient) -> None:
    token = _login(client)["device_token"]

    assert client.post("/v1/auth/logout", headers=_auth(token)).status_code == 204
    assert client.get("/v1/session", headers=_auth(token)).status_code == 401

    db = client.app.state.db
    with db.connect() as conn:
        assert conn.execute("SELECT COUNT(*) AS c FROM accounts").fetchone()["c"] == 1
        assert conn.execute("SELECT revoked_at FROM devices").fetchone()["revoked_at"]


def test_revoked_device_gets_no_pushes(client: TestClient) -> None:
    token = _login(client)["device_token"]
    db = client.app.state.db
    client.post("/v1/auth/logout", headers=_auth(token))

    assert db.list_devices_for_account(1) == []


def test_fcm_token_can_be_refreshed(client: TestClient) -> None:
    token = _login(client)["device_token"]

    response = client.post(
        "/v1/devices/fcm", headers=_auth(token), json={"fcm_token": "n" * 40}
    )

    assert response.status_code == 204
    with client.app.state.db.connect() as conn:
        assert conn.execute("SELECT fcm_token FROM devices").fetchone()["fcm_token"] == "n" * 40


def test_fcm_refresh_requires_device_token(client: TestClient) -> None:
    response = client.post("/v1/devices/fcm", headers=APP_KEY, json={"fcm_token": "n" * 40})
    assert response.status_code == 401


def test_login_works_without_fcm_token(client: TestClient) -> None:
    """Firebase выдаёт токен не всегда, а расписание и деньги нужны и без пушей.
    Токен донесёт POST /v1/devices/fcm, когда появится."""
    response = client.post(
        "/v1/auth/login",
        headers=APP_KEY,
        json={
            "login": "+79991234567",
            "password": PASSWORD,
            "device_id": "device-no-fcm",
        },
    )

    assert response.status_code == 200, response.text
    with client.app.state.db.connect() as conn:
        assert conn.execute(
            "SELECT fcm_token FROM devices WHERE device_id = 'device-no-fcm'"
        ).fetchone()["fcm_token"] == ""
