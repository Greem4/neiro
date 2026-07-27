from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path

from fastapi import Depends, FastAPI, Form, Header, HTTPException, Query, Request, Response, status
from fastapi.responses import HTMLResponse, PlainTextResponse
from fastapi.templating import Jinja2Templates

from app.config import Settings, get_settings
from app.dashboard import build_html_context, collect_dashboard_data, render_dashboard_text
from app.database import Database
from app.fcm import FcmSender
from app.poller import MOSCOW, PollService
from app.schemas import (
    AckEventsRequest,
    EventPayload,
    EventsResponse,
    HealthResponse,
    RegisterDeviceRequest,
    RegisterDeviceResponse,
)
from app.security import SecretBox, constant_time_equals
from app.yclients import YClientsClient

DASHBOARD_COOKIE = "admin_key"
DASHBOARD_COOKIE_MAX_AGE = 30 * 24 * 3600

templates = Jinja2Templates(directory=str(Path(__file__).resolve().parent.parent / "templates"))

logger = logging.getLogger(__name__)


def configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    configure_logging(settings.log_level)

    db = Database(settings.database_path)
    secret_box = SecretBox(settings.token_encryption_key)
    yclients_client = YClientsClient(settings)
    fcm_sender = FcmSender(settings)
    poll_service = PollService(
        settings=settings,
        database=db,
        secret_box=secret_box,
        yclients=yclients_client,
        fcm=fcm_sender,
    )

    app.state.db = db
    app.state.secret_box = secret_box
    app.state.poll_service = poll_service
    app.state.started_at = datetime.now(timezone.utc)

    poll_service.start()
    logger.info("neiro-push-events started")
    yield
    await poll_service.stop()
    await yclients_client.aclose()
    await fcm_sender.aclose()
    logger.info("neiro-push-events stopped")


app = FastAPI(title="Neiro Push Events", version="0.1.0", lifespan=lifespan)


def get_database(request: Request) -> Database:
    return request.app.state.db


def get_secret_box(request: Request) -> SecretBox:
    return request.app.state.secret_box


def get_poll_service(request: Request) -> PollService:
    return request.app.state.poll_service


def verify_api_key(
    authorization: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> None:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="missing bearer token"
        )
    token = authorization.removeprefix("Bearer ").strip()
    if not constant_time_equals(token, settings.api_key):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid api key")


def _admin_key(settings: Settings) -> str:
    return settings.admin_api_key or settings.api_key


def verify_admin_api_key(
    authorization: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> None:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="missing bearer token"
        )
    token = authorization.removeprefix("Bearer ").strip()
    if not constant_time_equals(token, _admin_key(settings)):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid admin api key")


@app.get(
    "/health",
    response_model=HealthResponse,
    dependencies=[Depends(verify_admin_api_key)],
)
async def health(
    settings: Settings = Depends(get_settings),
    db: Database = Depends(get_database),
    poll_service: PollService = Depends(get_poll_service),
) -> HealthResponse:
    stats = db.stats()
    return HealthResponse(
        fcm_configured=poll_service.fcm_configured,
        accounts=stats["accounts"],
        devices=stats["devices"],
        events_today=stats["events_today"],
        poll_day_seconds=settings.poll_interval_seconds,
        poll_night_seconds=settings.poll_night_interval_seconds,
    )


@app.post(
    "/v1/devices/register",
    response_model=RegisterDeviceResponse,
    dependencies=[Depends(verify_api_key)],
)
async def register_device(
    body: RegisterDeviceRequest,
    db: Database = Depends(get_database),
    secret_box: SecretBox = Depends(get_secret_box),
) -> RegisterDeviceResponse:
    account_id = db.upsert_account(
        company_id=body.company_id,
        staff_id=body.staff_id,
        partner_token_enc=secret_box.encrypt(body.partner_token),
        user_token_enc=secret_box.encrypt(body.user_token),
    )
    existing_device = db.get_device(body.device_id)
    db.upsert_device(
        account_id=account_id,
        device_id=body.device_id,
        fcm_token=body.fcm_token,
        label=body.label,
        app_version=body.app_version,
    )

    if existing_device is None:
        # Новое устройство стартует с конца журнала — решение пользователя
        # от 25.07.2026 (app.md §6.4): лента прошлых событий не нужна,
        # актуальное состояние телефон и так получает полным синком.
        last_event_id = db.get_max_event_id()
        db.update_device_ack(body.device_id, last_event_id)
    else:
        # Известное устройство (переустановка, повторная регистрация на
        # keepalive) курсор не трогает — иначе недоставленные события
        # потеряются молча.
        last_event_id = existing_device.last_ack_event_id or 0

    return RegisterDeviceResponse(
        account_id=account_id, device_id=body.device_id, last_event_id=last_event_id
    )


@app.delete(
    "/v1/devices/{device_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    dependencies=[Depends(verify_api_key)],
)
async def unregister_device(
    device_id: str,
    db: Database = Depends(get_database),
) -> Response:
    deleted = db.delete_device(device_id)
    if not deleted:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="not found")
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@app.get(
    "/v1/devices/{device_id}/events",
    response_model=EventsResponse,
    dependencies=[Depends(verify_api_key)],
)
async def get_device_events(
    device_id: str,
    since: int = Query(ge=0),
    limit: int = Query(default=100, ge=1, le=500),
    db: Database = Depends(get_database),
) -> EventsResponse:
    device = db.get_device(device_id)
    if device is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="not found")
    account = db.get_account(device.account_id)
    assert account is not None

    # Горизонт §6.4 плана: догон не тащит события про уже прошедшие занятия.
    today = datetime.now(MOSCOW).date().isoformat()
    rows = db.list_events_since(device.account_id, since, limit=limit + 1, min_date=today)
    has_more = len(rows) > limit
    rows = rows[:limit]

    events = [
        EventPayload(
            id=row.id,
            staff_id=account.staff_id,
            type=row.type,
            client_name=row.client_name,
            date=row.date,
            time=row.time,
            kind=row.kind,
            prev_date=row.prev_date,
            prev_time=row.prev_time,
        )
        for row in rows
    ]
    last_event_id = events[-1].id if events else since
    return EventsResponse(events=events, last_event_id=last_event_id, has_more=has_more)


@app.post(
    "/v1/devices/{device_id}/events/ack",
    status_code=status.HTTP_204_NO_CONTENT,
    dependencies=[Depends(verify_api_key)],
)
async def ack_device_events(
    device_id: str,
    body: AckEventsRequest,
    db: Database = Depends(get_database),
) -> Response:
    device = db.get_device(device_id)
    if device is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="not found")
    db.update_device_ack(device_id, body.last_event_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@app.get("/v1/admin/events", dependencies=[Depends(verify_admin_api_key)])
async def admin_events(
    limit: int = Query(default=50, ge=1, le=500),
    db: Database = Depends(get_database),
) -> dict:
    return {"events": db.list_recent_events_admin(limit)}


@app.get("/v1/admin/poll-log", dependencies=[Depends(verify_admin_api_key)])
async def admin_poll_log(
    limit: int = Query(default=50, ge=1, le=500),
    db: Database = Depends(get_database),
) -> dict:
    return {"poll_runs": db.list_poll_runs_admin(limit)}


@app.get(
    "/v1/admin/dashboard.txt",
    response_class=PlainTextResponse,
    dependencies=[Depends(verify_admin_api_key)],
)
async def admin_dashboard_text(
    request: Request,
    settings: Settings = Depends(get_settings),
    db: Database = Depends(get_database),
    poll_service: PollService = Depends(get_poll_service),
) -> str:
    data = collect_dashboard_data(db, poll_service, settings, request.app.state.started_at)
    return render_dashboard_text(data)


@app.get("/dashboard", response_class=HTMLResponse)
async def dashboard_page(
    request: Request,
    settings: Settings = Depends(get_settings),
    db: Database = Depends(get_database),
    poll_service: PollService = Depends(get_poll_service),
) -> HTMLResponse:
    cookie_value = request.cookies.get(DASHBOARD_COOKIE)
    authenticated = bool(cookie_value) and constant_time_equals(cookie_value, _admin_key(settings))
    context = {"authenticated": authenticated, "login_error": False}
    if authenticated:
        data = collect_dashboard_data(db, poll_service, settings, request.app.state.started_at)
        context.update(build_html_context(data))
    return templates.TemplateResponse(request, "dashboard.html", context)


@app.post("/dashboard", response_class=HTMLResponse)
@app.post("/dashboard/login", response_class=HTMLResponse)
async def dashboard_login(
    request: Request,
    key: str = Form(...),
    settings: Settings = Depends(get_settings),
    db: Database = Depends(get_database),
    poll_service: PollService = Depends(get_poll_service),
) -> Response:
    if not constant_time_equals(key, _admin_key(settings)):
        context = {"authenticated": False, "login_error": True}
        return templates.TemplateResponse(
            request, "dashboard.html", context, status_code=status.HTTP_401_UNAUTHORIZED
        )
    # Страница отдаётся сразу, без Location-редиректа. Публично сервис живёт под
    # /v2, и nginx на VPS срезает префикс (docs/push-events.md §7) — приложение
    # не знает, что смонтировано под /v2, поэтому абсолютный "/dashboard" в
    # Location уводил браузер мимо сервиса на 404 старого neiro-push.
    data = collect_dashboard_data(db, poll_service, settings, request.app.state.started_at)
    context = {"authenticated": True, "login_error": False}
    context.update(build_html_context(data))
    response = templates.TemplateResponse(request, "dashboard.html", context)
    response.set_cookie(
        DASHBOARD_COOKIE,
        key,
        max_age=DASHBOARD_COOKIE_MAX_AGE,
        httponly=True,
        secure=True,
        samesite="lax",
    )
    return response
