"""Вход по логину и паролю, выпуск и проверка `device_token`.

Единственное место сервиса, куда попадает пароль YClients. Он живёт внутри
одного запроса: уходит в YClients, обменивается на `user_token` и забывается —
ни в базу, ни в лог, ни в текст исключения не попадает
(RISKS.md § Пароль проходит через свой сервер).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

from fastapi import APIRouter, Depends, Header, HTTPException, Request, Response, status

from app.config import Settings, get_settings
from app.database import Database, RegisteredDevice, WatchedAccount
from app.schemas import (
    AccountPayload,
    FcmTokenRequest,
    LoginRequest,
    LoginResponse,
    SessionResponse,
)
from app.security import (
    SecretBox,
    constant_time_equals,
    generate_device_token,
    hash_device_token,
)
from app.yclients import (
    YClientsAuthError,
    YClientsClient,
    YClientsUpstreamError,
    match_staff_id,
    normalize_avatar_url,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/v1")


@dataclass(frozen=True)
class DeviceContext:
    device: RegisteredDevice
    account: WatchedAccount


def _bearer(authorization: str | None) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="missing bearer token"
        )
    return authorization.removeprefix("Bearer ").strip()


def get_database(request: Request) -> Database:
    return request.app.state.db


def get_secret_box(request: Request) -> SecretBox:
    return request.app.state.secret_box


def get_yclients(request: Request) -> YClientsClient:
    return request.app.state.yclients


def require_app_key(
    authorization: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> None:
    """Ключ приложения из APK. Открывает ровно одну дверь — вход."""
    if not constant_time_equals(_bearer(authorization), settings.api_key):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid api key")


def require_device(
    authorization: str | None = Header(default=None),
    db: Database = Depends(get_database),
) -> DeviceContext:
    """`device_token` → устройство → аккаунт.

    Отозванный токен не отличается от неизвестного: оба дают `401`, и телефон
    в обоих случаях делает одно и то же — выходит в локальный режим.

    Флаг `reauth_required` здесь намеренно **не** проверяется: `GET /v1/session`
    обязан ответить именно тогда, когда он взведён, — иначе приложению неоткуда
    узнать, что нужен повторный вход. Отбивает такие запросы `require_live_account`,
    его берут прокси и события.
    """
    token = _bearer(authorization)
    device = db.get_device_by_token_hash(hash_device_token(token))
    if device is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_device_token"
        )
    account = db.get_account(device.account_id)
    if account is None:
        # Устройство пережило свой аккаунт — сирота из времён до PRAGMA
        # foreign_keys. Для телефона это тот же «доступа больше нет».
        logger.warning("device %s references missing account %s", device.device_id, device.account_id)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_device_token"
        )
    db.touch_device_seen(device.device_id)
    return DeviceContext(device=device, account=account)


def require_live_account(context: DeviceContext = Depends(require_device)) -> DeviceContext:
    """То же, плюс отказ, когда `user_token` протух.

    `409`, а не `401`: аккаунт жив, устройство зарегистрировано, пуши идут —
    не хватает только пароля. Приложение просит войти заново, но не выходит
    в локальный режим (ARCHITECTURE.md § Что происходит при отказах).
    """
    if context.account.reauth_required:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="reauth_required")
    return context


def _account_payload(profile: dict) -> AccountPayload:
    return AccountPayload(
        company_id=profile["company_id"],
        staff_id=profile["staff_id"],
        user_name=profile["user_name"],
        avatar_url=profile["avatar_url"],
    )


@router.post("/auth/login", response_model=LoginResponse, dependencies=[Depends(require_app_key)])
async def login(
    body: LoginRequest,
    settings: Settings = Depends(get_settings),
    db: Database = Depends(get_database),
    secret_box: SecretBox = Depends(get_secret_box),
    yclients: YClientsClient = Depends(get_yclients),
) -> LoginResponse:
    company_id = settings.yclients_company_id

    try:
        auth_data = await yclients.login(body.login, body.password)
    except YClientsAuthError:
        # Ни логина, ни тем более пароля в сообщении: лог сервиса читается
        # глазами и попадает в бэкапы.
        logger.info("login rejected by YClients for device %s", body.device_id)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_credentials"
        ) from None
    except YClientsUpstreamError as exc:
        logger.warning("YClients unavailable on login: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY, detail="upstream_error"
        ) from None

    user_name = str(auth_data.get("name") or "").strip()
    try:
        staff = await yclients.fetch_staff(company_id)
    except YClientsUpstreamError as exc:
        logger.warning("YClients unavailable on book_staff: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY, detail="upstream_error"
        ) from None

    staff_id = match_staff_id(user_name, staff)
    if staff_id is None:
        # Раньше это кончалось пустым календарём через полчаса; теперь вход
        # отклоняется сразу и с причиной.
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="staff_not_found",
        )

    account_id = db.upsert_account(
        company_id=company_id,
        staff_id=staff_id,
        user_token_enc=secret_box.encrypt(str(auth_data["user_token"])),
        user_login=body.login,
        user_name=user_name or None,
        avatar_url=normalize_avatar_url(auth_data.get("avatar")),
    )

    existing = db.get_device(body.device_id)
    device_token = generate_device_token()
    db.upsert_device(
        account_id=account_id,
        device_id=body.device_id,
        token_hash=hash_device_token(device_token),
        fcm_token=body.fcm_token,
        label=body.label,
        app_version=body.app_version,
    )

    if existing is None:
        # Новое устройство стартует с конца журнала: лента прошлых событий ему
        # не нужна, актуальное состояние приедет полным синком.
        last_event_id = db.get_max_event_id()
        db.update_device_ack(body.device_id, last_event_id)
    else:
        # Известное устройство курсор сохраняет — иначе события, накопившиеся
        # за время без связи, потерялись бы молча.
        last_event_id = existing.last_ack_event_id or 0

    profile = db.get_account_profile(account_id)
    assert profile is not None
    logger.info(
        "login ok: account=%s staff=%s device=%s", account_id, staff_id, body.device_id
    )
    return LoginResponse(
        device_token=device_token,
        account=_account_payload(profile),
        last_event_id=last_event_id,
    )


@router.post("/auth/logout", status_code=status.HTTP_204_NO_CONTENT)
async def logout(
    context: DeviceContext = Depends(require_device),
    db: Database = Depends(get_database),
) -> Response:
    """Аккаунт остаётся вместе с `user_token`: на нём может висеть другое
    устройство, а поллер и так пропустит аккаунт, которому некому слать."""
    db.revoke_device(context.device.device_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/session", response_model=SessionResponse)
async def session(
    context: DeviceContext = Depends(require_device),
    db: Database = Depends(get_database),
) -> SessionResponse:
    profile = db.get_account_profile(context.account.id)
    assert profile is not None
    return SessionResponse(
        account=_account_payload(profile),
        reauth_required=context.account.reauth_required,
        last_event_id=context.device.last_ack_event_id or 0,
    )


@router.post("/devices/fcm", status_code=status.HTTP_204_NO_CONTENT)
async def update_fcm_token(
    body: FcmTokenRequest,
    context: DeviceContext = Depends(require_device),
    db: Database = Depends(get_database),
) -> Response:
    db.update_device_fcm(context.device.device_id, body.fcm_token)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
