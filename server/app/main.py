from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Response, status

from app.admin import send_test_push
from app.config import Settings, get_settings
from app.database import Database
from app.fcm import FcmSender
from app.poller import PollService, get_poll_service
from app.schemas import (
    AdminOverviewResponse,
    HealthResponse,
    RegisterDeviceRequest,
    RegisterDeviceResponse,
    TestPushRequest,
    TestPushResponse,
)
from app.security import SecretBox, constant_time_equals

logger = logging.getLogger(__name__)


def configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )


def verify_api_key(
    authorization: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> None:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="missing bearer token",
        )
    token = authorization.removeprefix("Bearer ").strip()
    if not constant_time_equals(token, settings.api_key):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid api key",
        )


def verify_admin_api_key(
    authorization: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> None:
    admin_key = settings.admin_api_key or settings.api_key
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="missing bearer token",
        )
    token = authorization.removeprefix("Bearer ").strip()
    if not constant_time_equals(token, admin_key):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="invalid admin api key",
        )


def get_database(settings: Settings = Depends(get_settings)) -> Database:
    return Database(settings.database_path)


def get_secret_box(settings: Settings = Depends(get_settings)) -> SecretBox:
    return SecretBox(settings.token_encryption_key)


def get_fcm_sender(settings: Settings = Depends(get_settings)) -> FcmSender:
    return FcmSender(settings)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    configure_logging(settings.log_level)
    poll_service = get_poll_service()
    poll_service.start()
    logger.info("neiro-push started")
    yield
    await poll_service.stop()
    logger.info("neiro-push stopped")


app = FastAPI(title="Neiro Push", version="1.0.0", lifespan=lifespan)


@app.get(
    "/health",
    response_model=HealthResponse,
    dependencies=[Depends(verify_admin_api_key)],
)
async def health(
    settings: Settings = Depends(get_settings),
    database: Database = Depends(get_database),
) -> HealthResponse:
    stats = database.stats()
    poll_service = get_poll_service()
    return HealthResponse(
        fcm_configured=poll_service.fcm_configured,
        accounts=stats["accounts"],
        devices=stats["devices"],
        poll_day_seconds=settings.poll_interval_seconds,
        poll_night_seconds=settings.poll_night_interval_seconds,
    )


@app.get(
    "/v1/admin/overview",
    response_model=AdminOverviewResponse,
    dependencies=[Depends(verify_admin_api_key)],
)
async def admin_overview(
    database: Database = Depends(get_database),
) -> AdminOverviewResponse:
    return AdminOverviewResponse(
        accounts=database.list_accounts_admin(),
        devices=database.list_devices_admin(),
    )


@app.post(
    "/v1/admin/test-push",
    response_model=TestPushResponse,
    dependencies=[Depends(verify_admin_api_key)],
)
async def admin_test_push(
    body: TestPushRequest,
    database: Database = Depends(get_database),
    fcm: FcmSender = Depends(get_fcm_sender),
) -> TestPushResponse:
    try:
        result = await send_test_push(
            database=database,
            fcm=fcm,
            device_id=body.device_id,
            account_id=body.account_id,
        )
    except RuntimeError as exc:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail=str(exc),
        ) from exc

    return TestPushResponse(**result)


@app.post(
    "/v1/devices/register",
    response_model=RegisterDeviceResponse,
    dependencies=[Depends(verify_api_key)],
)
async def register_device(
    body: RegisterDeviceRequest,
    database: Database = Depends(get_database),
    secret_box: SecretBox = Depends(get_secret_box),
) -> RegisterDeviceResponse:
    account_id = database.upsert_account(
        company_id=body.company_id,
        staff_id=body.staff_id,
        partner_token_enc=secret_box.encrypt(body.partner_token),
        user_token_enc=secret_box.encrypt(body.user_token),
    )
    database.upsert_device(
        account_id=account_id,
        device_id=body.device_id,
        fcm_token=body.fcm_token,
        label=body.label,
        app_version=body.app_version,
    )
    return RegisterDeviceResponse(account_id=account_id, device_id=body.device_id)


@app.delete(
    "/v1/devices/{device_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    dependencies=[Depends(verify_api_key)],
)
async def unregister_device(
    device_id: str,
    database: Database = Depends(get_database),
) -> Response:
    deleted = database.delete_device(device_id)
    if not deleted:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="not found")
    return Response(status_code=status.HTTP_204_NO_CONTENT)
