import json
from pathlib import Path

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.main import app
from app.security import hash_device_token

RAW_BODY = json.dumps(
    {
        "success": True,
        "data": [{"id": 42, "staff_id": 2008329, "comment": "как есть"}],
        "meta": {"total_count": 1},
    },
    ensure_ascii=False,
).encode("utf-8")


class FakeYClients:
    """Записывает, что у него попросили, и отдаёт заготовленный ответ."""

    def __init__(self) -> None:
        self.calls: list[dict] = []
        self.status_code = 200
        self.body = RAW_BODY
        self.raise_error: Exception | None = None

    async def fetch_raw(self, path: str, *, user_token: str, params: dict | None = None):
        self.calls.append({"path": path, "user_token": user_token, "params": params or {}})
        if self.raise_error:
            raise self.raise_error
        return httpx.Response(
            status_code=self.status_code,
            content=self.body,
            headers={"content-type": "application/json"},
            request=httpx.Request("GET", f"https://api.yclients.com{path}"),
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


DEVICE_TOKEN = "device-token-for-tests"
AUTH = {"Authorization": f"Bearer {DEVICE_TOKEN}"}


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


# --- проброс как есть ---------------------------------------------------------


def test_body_and_status_pass_through_untouched(
    client: TestClient, yclients: FakeYClients, account: int
) -> None:
    """Побайтовое совпадение — то самое условие готовности этапа: модели и
    парсеры на телефоне не переписываются."""
    response = client.get(
        "/v1/yclients/records",
        headers=AUTH,
        params={"start_date": "2026-08-01", "end_date": "2026-08-31"},
    )

    assert response.status_code == 200
    assert response.content == RAW_BODY
    assert response.json()["meta"]["total_count"] == 1


def test_yclients_own_codes_pass_through(
    client: TestClient, yclients: FakeYClients, account: int
) -> None:
    """403 на зарплате без прав и 422 на кривом диапазоне — это ответы YClients,
    приложение уже умеет их разбирать. Прокси их не переводит в свои."""
    for code in (403, 422):
        yclients.status_code = code
        yclients.body = b'{"success":false,"meta":{"message":"nope"}}'
        response = client.get(
            "/v1/yclients/salary/daily",
            headers=AUTH,
            params={"date_from": "2026-08-01", "date_to": "2026-08-31"},
        )
        assert response.status_code == code
        assert response.json()["meta"]["message"] == "nope"


# --- подстановка company_id и staff_id ---------------------------------------


def test_staff_and_company_come_from_account_not_request(
    client: TestClient, yclients: FakeYClients, account: int
) -> None:
    """Чужие записи не попросить: идентификаторы берутся из аккаунта, а то, что
    прислал телефон, игнорируется."""
    client.get(
        "/v1/yclients/records",
        headers=AUTH,
        params={
            "start_date": "2026-08-01",
            "end_date": "2026-08-31",
            "staff_id": 999999,
            "company_id": 999999,
        },
    )

    call = yclients.calls[-1]
    assert call["path"] == "/records/520135"
    assert call["params"]["staff_id"] == 2008329


def test_every_endpoint_targets_the_right_yclients_path(
    client: TestClient, yclients: FakeYClients, account: int
) -> None:
    client.get("/v1/yclients/clients", headers=AUTH)
    assert yclients.calls[-1]["path"] == "/clients/520135"

    client.get("/v1/yclients/staff", headers=AUTH)
    assert yclients.calls[-1]["path"] == "/book_staff/520135"

    client.get(
        "/v1/yclients/salary/daily",
        headers=AUTH,
        params={"date_from": "2026-08-01", "date_to": "2026-08-31"},
    )
    assert yclients.calls[-1]["path"] == (
        "/company/520135/salary/period/staff/daily/2008329/"
    )

    client.get(
        "/v1/yclients/salary/calculations",
        headers=AUTH,
        params={"date_from": "2026-08-01", "date_to": "2026-08-31"},
    )
    assert yclients.calls[-1]["path"] == (
        "/company/520135/salary/payroll/staff/2008329/calculation/"
    )

    client.get("/v1/yclients/salary/calculations/777", headers=AUTH)
    assert yclients.calls[-1]["path"] == (
        "/company/520135/salary/payroll/staff/2008329/calculation/777"
    )


def test_pagination_passes_through(
    client: TestClient, yclients: FakeYClients, account: int
) -> None:
    """Цикл по страницам остаётся в YClientsRepository — его не трогаем."""
    client.get(
        "/v1/yclients/records",
        headers=AUTH,
        params={
            "start_date": "2026-08-01",
            "end_date": "2026-08-31",
            "page": 3,
            "count": 50,
            "changed_after": "2026-08-01 10:00:00",
            "with_deleted": 1,
        },
    )

    params = yclients.calls[-1]["params"]
    assert params["page"] == 3
    assert params["count"] == 50
    assert params["changed_after"] == "2026-08-01 10:00:00"
    assert params["with_deleted"] == 1


# --- отказы -------------------------------------------------------------------


def test_three_consecutive_401_trigger_reauth_not_the_first(
    client: TestClient, yclients: FakeYClients, account: int
) -> None:
    """Авария YClients не должна разлогинивать всех: флаг ставится только после
    третьего подряд 401 (RISKS.md § Протухший user_token)."""
    yclients.status_code = 401
    db = client.app.state.db

    for attempt in (1, 2):
        response = client.get("/v1/yclients/staff", headers=AUTH)
        assert response.status_code == 409
        assert response.json()["detail"] == "reauth_required"
        assert db.get_account(account).reauth_required is False, f"попытка {attempt}"

    response = client.get("/v1/yclients/staff", headers=AUTH)
    assert response.status_code == 409
    assert db.get_account(account).reauth_required is True


def test_successful_answer_resets_the_401_streak(
    client: TestClient, yclients: FakeYClients, account: int
) -> None:
    """Считаем именно подряд идущие: два отказа, успех, ещё два — не три."""
    db = client.app.state.db
    yclients.status_code = 401
    client.get("/v1/yclients/staff", headers=AUTH)
    client.get("/v1/yclients/staff", headers=AUTH)

    yclients.status_code = 200
    assert client.get("/v1/yclients/staff", headers=AUTH).status_code == 200

    yclients.status_code = 401
    client.get("/v1/yclients/staff", headers=AUTH)
    client.get("/v1/yclients/staff", headers=AUTH)

    assert db.get_account(account).reauth_required is False


def test_reauth_required_account_is_refused_with_409(
    client: TestClient, yclients: FakeYClients, account: int
) -> None:
    """Дальше прокси такой аккаунт не пускает вовсе — запросы к YClients не идут."""
    client.app.state.db.set_reauth_required(account, True)

    response = client.get("/v1/yclients/staff", headers=AUTH)

    assert response.status_code == 409
    assert response.json()["detail"] == "reauth_required"
    assert yclients.calls == []


def test_upstream_5xx_becomes_502(
    client: TestClient, yclients: FakeYClients, account: int
) -> None:
    yclients.status_code = 503
    response = client.get("/v1/yclients/staff", headers=AUTH)
    assert response.status_code == 502
    assert response.json()["detail"] == "upstream_error"


def test_timeout_becomes_504(
    client: TestClient, yclients: FakeYClients, account: int
) -> None:
    yclients.raise_error = httpx.ReadTimeout("too slow")
    response = client.get("/v1/yclients/staff", headers=AUTH)
    assert response.status_code == 504
    assert response.json()["detail"] == "upstream_timeout"


def test_network_failure_becomes_502(
    client: TestClient, yclients: FakeYClients, account: int
) -> None:
    yclients.raise_error = httpx.ConnectError("no route")
    response = client.get("/v1/yclients/staff", headers=AUTH)
    assert response.status_code == 502
    assert response.json()["detail"] == "upstream_error"


def test_oversized_response_is_refused(
    client: TestClient, yclients: FakeYClients, account: int
) -> None:
    yclients.body = b"x" * (5 * 1024 * 1024 + 1)
    response = client.get("/v1/yclients/staff", headers=AUTH)
    assert response.status_code == 502
    assert response.json()["detail"] == "upstream_too_large"


def test_proxy_requires_device_token(client: TestClient, yclients: FakeYClients) -> None:
    assert client.get("/v1/yclients/staff").status_code == 401
    assert (
        client.get("/v1/yclients/staff", headers={"Authorization": "Bearer test-api-key"})
        .status_code
        == 401
    )
    assert yclients.calls == []


def test_revoked_device_loses_proxy_access(
    client: TestClient, yclients: FakeYClients, account: int
) -> None:
    client.app.state.db.revoke_device("device-0001")

    assert client.get("/v1/yclients/staff", headers=AUTH).status_code == 401
    assert yclients.calls == []
