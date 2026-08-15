from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.main import app
from app.ratelimit import RateLimiter, client_ip
from app.security import hash_device_token
from app.yclients import YClientsAuthError

DEVICE_TOKEN = "device-token-for-tests"
AUTH = {"Authorization": f"Bearer {DEVICE_TOKEN}"}
APP_KEY = {"Authorization": "Bearer test-api-key"}


class FakeYClients:
    def __init__(self) -> None:
        self.auth_error: Exception | None = None
        self.calls = 0

    async def login(self, login: str, password: str) -> dict:
        self.calls += 1
        if self.auth_error:
            raise self.auth_error
        return {"id": 7, "user_token": "ut", "name": "Светлана Зеленкина", "avatar": None}

    async def fetch_staff(self, company_id: int) -> list[dict]:
        return [{"id": 2008329, "name": "Светлана Зеленкина", "fired": 0}]

    async def fetch_raw(self, path: str, *, user_token: str, params: dict | None = None):
        import httpx

        self.calls += 1
        return httpx.Response(
            200,
            content=b'{"success":true,"data":[]}',
            headers={"content-type": "application/json"},
            request=httpx.Request("GET", "https://api.yclients.com" + path),
        )


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


def _seed(client: TestClient) -> int:
    db = client.app.state.db
    account_id = db.upsert_account(
        520135, 2008329, client.app.state.secret_box.encrypt("ut"), "+79991234567"
    )
    db.upsert_device(account_id, "device-0001", hash_device_token(DEVICE_TOKEN), "f" * 32, None, None)
    return account_id


def _login_body(device_id: str = "device-0001", password: str = "pw") -> dict:
    return {
        "login": "+79991234567",
        "password": password,
        "device_id": device_id,
        "fcm_token": "f" * 32,
    }


# --- сам лимитер --------------------------------------------------------------


def test_limiter_allows_up_to_the_limit_then_refuses() -> None:
    limiter = RateLimiter()
    assert [limiter.check("k", 3, 60) for _ in range(3)] == [None, None, None]
    retry = limiter.check("k", 3, 60)
    assert retry is not None and 0 < retry <= 60


def test_hammering_does_not_extend_the_block() -> None:
    """Отбитая попытка в окно не пишется — иначе клиент, долбящий без пауз,
    продлевал бы себе блокировку бесконечно."""
    limiter = RateLimiter()
    for _ in range(3):
        limiter.check("k", 3, 60)
    first = limiter.check("k", 3, 60)
    for _ in range(20):
        limiter.check("k", 3, 60)
    later = limiter.check("k", 3, 60)
    assert later is not None and first is not None
    assert later <= first


def test_reset_clears_the_counter() -> None:
    limiter = RateLimiter()
    for _ in range(3):
        limiter.check("k", 3, 60)
    limiter.reset("k")
    assert limiter.check("k", 3, 60) is None


def test_keys_are_independent() -> None:
    limiter = RateLimiter()
    for _ in range(3):
        limiter.check("a", 3, 60)
    assert limiter.check("b", 3, 60) is None


def test_purge_drops_stale_keys() -> None:
    limiter = RateLimiter()
    limiter.check("k", 3, 60)
    limiter.purge(older_than_seconds=0)
    assert limiter._hits == {}


def test_client_ip_uses_real_ip_and_ignores_forwarded_chain() -> None:
    """X-Forwarded-For клиент подставляет сам: nginx дописывает настоящий адрес
    в конец списка, а первым остаётся присланный. Верить можно только
    X-Real-IP, его nginx ставит из $remote_addr (аудит 14.08.26, K1)."""

    class Req:
        headers = {"x-forwarded-for": "1.2.3.4, 10.0.0.1", "x-real-ip": "5.6.7.8"}
        client = None

    assert client_ip(Req()) == "5.6.7.8"


def test_client_ip_ignores_lone_forwarded_header() -> None:
    """Без X-Real-IP уходим к концу туннеля, а не к слову клиента."""

    class Peer:
        host = "10.0.0.1"

    class Req:
        headers = {"x-forwarded-for": "203.0.113.7"}
        client = Peer()

    assert client_ip(Req()) == "10.0.0.1"


def test_client_ip_falls_back_to_peer() -> None:
    class Peer:
        host = "10.0.0.1"

    class Req:
        headers: dict = {}
        client = Peer()

    assert client_ip(Req()) == "10.0.0.1"


# --- лимит входа --------------------------------------------------------------


def test_sixth_login_attempt_is_429_with_retry_after(
    client: TestClient, yclients: FakeYClients
) -> None:
    yclients.auth_error = YClientsAuthError("nope")
    for _ in range(5):
        assert client.post("/v1/auth/login", headers=APP_KEY, json=_login_body()).status_code == 401

    response = client.post("/v1/auth/login", headers=APP_KEY, json=_login_body())

    assert response.status_code == 429
    assert response.json()["detail"] == "too_many_attempts"
    assert int(response.headers["Retry-After"]) > 0


def test_blocked_login_never_reaches_yclients(
    client: TestClient, yclients: FakeYClients
) -> None:
    """Смысл лимита в том, чтобы перебор не доходил до YClients вовсе."""
    yclients.auth_error = YClientsAuthError("nope")
    for _ in range(5):
        client.post("/v1/auth/login", headers=APP_KEY, json=_login_body())
    before = yclients.calls

    client.post("/v1/auth/login", headers=APP_KEY, json=_login_body())

    assert yclients.calls == before


def test_changing_device_id_does_not_bypass_the_limit(
    client: TestClient, yclients: FakeYClients
) -> None:
    """Второй ключ — по IP: иначе перебор просто менял бы device_id."""
    yclients.auth_error = YClientsAuthError("nope")
    for i in range(5):
        client.post("/v1/auth/login", headers=APP_KEY, json=_login_body(f"device-{i:04d}"))

    response = client.post(
        "/v1/auth/login", headers=APP_KEY, json=_login_body("device-9999")
    )

    assert response.status_code == 429


def test_changing_forwarded_header_does_not_bypass_the_limit(
    client: TestClient, yclients: FakeYClients
) -> None:
    """Самый дешёвый обход: оба ключа лимита были под контролем клиента —
    device_id в теле и X-Forwarded-For в заголовках. Считаем по X-Real-IP."""
    yclients.auth_error = YClientsAuthError("nope")
    for i in range(5):
        client.post(
            "/v1/auth/login",
            headers={**APP_KEY, "X-Real-IP": "203.0.113.9", "X-Forwarded-For": f"198.51.100.{i}"},
            json=_login_body(f"device-{i:04d}"),
        )

    response = client.post(
        "/v1/auth/login",
        headers={**APP_KEY, "X-Real-IP": "203.0.113.9", "X-Forwarded-For": "198.51.100.99"},
        json=_login_body("device-9999"),
    )

    assert response.status_code == 429


def test_successful_login_clears_the_counter(
    client: TestClient, yclients: FakeYClients
) -> None:
    """Пять честных входов подряд не должны запирать дверь перед хозяином."""
    yclients.auth_error = YClientsAuthError("nope")
    for _ in range(4):
        client.post("/v1/auth/login", headers=APP_KEY, json=_login_body())

    yclients.auth_error = None
    assert client.post("/v1/auth/login", headers=APP_KEY, json=_login_body()).status_code == 200

    yclients.auth_error = YClientsAuthError("nope")
    assert client.post("/v1/auth/login", headers=APP_KEY, json=_login_body()).status_code == 401


# --- лимит устройства ---------------------------------------------------------


def test_device_over_sixty_requests_per_minute_is_429(
    client: TestClient, yclients: FakeYClients
) -> None:
    _seed(client)
    for _ in range(60):
        assert client.get("/v1/yclients/staff", headers=AUTH).status_code == 200

    response = client.get("/v1/yclients/staff", headers=AUTH)

    assert response.status_code == 429
    assert response.json()["detail"] == "rate_limited"
    assert int(response.headers["Retry-After"]) > 0


# --- отзыв и сброс через админку ---------------------------------------------


def test_admin_can_revoke_device(client: TestClient, yclients: FakeYClients) -> None:
    _seed(client)
    admin = {"Authorization": "Bearer test-admin-key"}

    response = client.post("/v1/admin/devices/device-0001/revoke", headers=admin)

    assert response.status_code == 200
    assert response.json()["revoked"] is True
    assert client.get("/v1/yclients/staff", headers=AUTH).status_code == 401


def test_admin_revoke_requires_admin_key(client: TestClient, yclients: FakeYClients) -> None:
    _seed(client)
    assert client.post("/v1/admin/devices/device-0001/revoke").status_code == 401
    assert client.post("/v1/admin/devices/device-0001/revoke", headers=APP_KEY).status_code == 401
    assert client.get("/v1/yclients/staff", headers=AUTH).status_code == 200


def test_admin_revoke_unknown_device_is_404(client: TestClient) -> None:
    admin = {"Authorization": "Bearer test-admin-key"}
    assert client.post("/v1/admin/devices/no-such/revoke", headers=admin).status_code == 404


def test_admin_can_reset_account(client: TestClient, yclients: FakeYClients) -> None:
    account_id = _seed(client)
    admin = {"Authorization": "Bearer test-admin-key"}

    response = client.post(f"/v1/admin/accounts/{account_id}/reset", headers=admin)

    assert response.status_code == 200
    # Устройство живо, но прокси просит пароль.
    assert client.get("/v1/yclients/staff", headers=AUTH).status_code == 409
    assert client.get("/v1/session", headers=AUTH).json()["reauth_required"] is True


def test_admin_reset_unknown_account_is_404(client: TestClient) -> None:
    admin = {"Authorization": "Bearer test-admin-key"}
    assert client.post("/v1/admin/accounts/999/reset", headers=admin).status_code == 404


# --- кнопки в дашборде --------------------------------------------------------


def test_dashboard_revoke_button_works(client: TestClient, yclients: FakeYClients) -> None:
    _seed(client)
    client.cookies.set("admin_key", "test-admin-key")

    response = client.post("/dashboard/devices/device-0001/revoke")

    assert response.status_code == 200
    assert "Устройство отозвано" in response.text
    assert client.get("/v1/yclients/staff", headers=AUTH).status_code == 401


def test_dashboard_reset_button_works(client: TestClient, yclients: FakeYClients) -> None:
    _seed(client)
    client.cookies.set("admin_key", "test-admin-key")

    response = client.post("/dashboard/devices/device-0001/reset-account")

    assert response.status_code == 200
    assert "повторный вход" in response.text
    assert client.get("/v1/yclients/staff", headers=AUTH).status_code == 409


def test_dashboard_buttons_require_cookie(client: TestClient, yclients: FakeYClients) -> None:
    _seed(client)
    assert client.post("/dashboard/devices/device-0001/revoke").status_code == 401
    assert client.post("/dashboard/devices/device-0001/reset-account").status_code == 401
    assert client.get("/v1/yclients/staff", headers=AUTH).status_code == 200


def test_device_page_shows_access_controls(client: TestClient, yclients: FakeYClients) -> None:
    _seed(client)
    client.cookies.set("admin_key", "test-admin-key")

    page = client.get("/dashboard/devices/device-0001").text

    assert 'action="device-0001/revoke"' in page
    assert 'action="device-0001/reset-account"' in page
    assert 'action="device-0001/delete"' in page


def test_device_page_forms_point_where_browser_would_send_them(
    client: TestClient, yclients: FakeYClients
) -> None:
    """Кнопки бьют туда, куда их отправит браузер, а не туда, где удобно тесту.

    Адрес страницы — .../devices/{id} без завершающего слеша, поэтому базой
    для относительного action браузер берёт .../devices/. Голый action="revoke"
    уходил в .../devices/revoke — страницу устройства с именем "revoke", где
    POST не разрешён, и все три кнопки отвечали 405.
    """
    import re
    from urllib.parse import urljoin

    _seed(client)
    client.cookies.set("admin_key", "test-admin-key")

    page_url = "http://testserver/dashboard/devices/device-0001"
    actions = re.findall(r'<form method="post" action="([^"]+)"', client.get(page_url).text)
    assert len(actions) == 3

    for action in actions:
        resolved = urljoin(page_url, action)
        response = client.post(resolved, follow_redirects=False)
        assert response.status_code != 405, f"{action} → {resolved} отдал 405"
        assert response.status_code in (200, 303), f"{action} → {response.status_code}"


# --- удаление устройства ------------------------------------------------------


def test_admin_can_delete_device(client: TestClient, yclients: FakeYClients) -> None:
    """Удаление уносит строку целиком — для телефона это тот же «доступа нет»."""
    _seed(client)
    admin = {"Authorization": "Bearer test-admin-key"}

    response = client.delete("/v1/admin/devices/device-0001", headers=admin)

    assert response.status_code == 200
    assert response.json()["deleted"] is True
    assert client.app.state.db.get_device_admin("device-0001") is None
    assert client.get("/v1/yclients/staff", headers=AUTH).status_code == 401


def test_admin_delete_requires_admin_key(client: TestClient, yclients: FakeYClients) -> None:
    _seed(client)
    assert client.delete("/v1/admin/devices/device-0001").status_code == 401
    assert client.delete("/v1/admin/devices/device-0001", headers=APP_KEY).status_code == 401
    assert client.get("/v1/yclients/staff", headers=AUTH).status_code == 200


def test_admin_delete_unknown_device_is_404(client: TestClient) -> None:
    admin = {"Authorization": "Bearer test-admin-key"}
    assert client.delete("/v1/admin/devices/no-such", headers=admin).status_code == 404


def test_dashboard_delete_removes_device_and_redirects(
    client: TestClient, yclients: FakeYClients
) -> None:
    """Страницы устройства после удаления не существует, поэтому кнопка уводит
    на список — обычный POST/Redirect/GET."""
    _seed(client)
    client.cookies.set("admin_key", "test-admin-key")

    response = client.post("/dashboard/devices/device-0001/delete", follow_redirects=False)

    assert response.status_code == 303
    assert response.headers["location"].endswith("dashboard?deleted=1")
    assert client.app.state.db.get_device_admin("device-0001") is None
    assert client.get("/v1/yclients/staff", headers=AUTH).status_code == 401
    assert client.get("/dashboard/devices/device-0001").status_code == 404


def test_dashboard_shows_banner_after_delete(client: TestClient, yclients: FakeYClients) -> None:
    client.cookies.set("admin_key", "test-admin-key")

    page = client.get("/dashboard", params={"deleted": 1}).text

    assert "Устройство удалено" in page


def test_device_lifecycle_login_work_delete_login_again(
    client: TestClient, yclients: FakeYClients
) -> None:
    """Полный круг: телефон вошёл, работал, его удалили из дашборда, он вошёл снова.

    Проверяет главное обещание кнопки: удаление отбирает доступ, но не ломает
    возможность вернуться — аккаунт и его user_token остаются на месте.
    """
    # 1. Вход: сервис заводит аккаунт и устройство, выдаёт device_token.
    login = client.post("/v1/auth/login", headers=APP_KEY, json=_login_body("phone-00001"))
    assert login.status_code == 200
    token = login.json()["device_token"]
    phone = {"Authorization": f"Bearer {token}"}

    # 2. Токен работает: прокси пускает.
    assert client.get("/v1/yclients/staff", headers=phone).status_code == 200
    assert client.app.state.db.get_device_admin("phone-00001") is not None

    # 3. Удаление кнопкой из дашборда.
    client.cookies.set("admin_key", "test-admin-key")
    deleted = client.post("/dashboard/devices/phone-00001/delete", follow_redirects=False)
    assert deleted.status_code == 303
    client.cookies.delete("admin_key")

    # 4. Доступ пропал, устройства в списке нет.
    assert client.get("/v1/yclients/staff", headers=phone).status_code == 401
    assert client.app.state.db.get_device_admin("phone-00001") is None

    # 5. Повторный вход возвращает устройство — с новым токеном.
    again = client.post("/v1/auth/login", headers=APP_KEY, json=_login_body("phone-00001"))
    assert again.status_code == 200
    new_token = again.json()["device_token"]
    assert new_token != token
    assert client.get("/v1/yclients/staff", headers={"Authorization": f"Bearer {new_token}"}).status_code == 200
    # Старый токен так и остался мёртвым.
    assert client.get("/v1/yclients/staff", headers=phone).status_code == 401


def test_deleting_one_device_leaves_the_others_alone(
    client: TestClient, yclients: FakeYClients
) -> None:
    """Удаляют тестовую строку — рабочий телефон рядом не должен пострадать."""
    first = client.post("/v1/auth/login", headers=APP_KEY, json=_login_body("phone-keep-01"))
    second = client.post("/v1/auth/login", headers=APP_KEY, json=_login_body("phone-drop-01"))
    keep = {"Authorization": f"Bearer {first.json()['device_token']}"}

    client.cookies.set("admin_key", "test-admin-key")
    client.post("/dashboard/devices/phone-drop-01/delete", follow_redirects=False)
    client.cookies.delete("admin_key")

    assert client.app.state.db.get_device_admin("phone-drop-01") is None
    assert client.app.state.db.get_device_admin("phone-keep-01") is not None
    assert client.get("/v1/yclients/staff", headers=keep).status_code == 200
    assert second.status_code == 200


def test_dashboard_delete_requires_cookie(client: TestClient, yclients: FakeYClients) -> None:
    _seed(client)
    assert client.post("/dashboard/devices/device-0001/delete").status_code == 401
    assert client.get("/v1/yclients/staff", headers=AUTH).status_code == 200
