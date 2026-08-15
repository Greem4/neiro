from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote

from fastapi import Depends, FastAPI, Form, Header, HTTPException, Query, Request, Response, status
from fastapi.responses import HTMLResponse, PlainTextResponse, RedirectResponse
from fastapi.templating import Jinja2Templates

from app import auth, device_events, proxy
from app.config import Settings, get_settings
from app.dashboard import (
    DEVICE_EVENTS_LIMIT,
    build_device_events_context,
    build_device_html_context,
    build_html_context,
    build_poll_runs_context,
    build_status_context,
    collect_dashboard_data,
    collect_status_data,
    render_dashboard_text,
)
from app.database import Database
from app.fcm import FcmSender
from app.poller import MOSCOW, PollService
from app.ratelimit import RateLimiter
from app.schemas import HealthResponse
from app.security import SecretBox, constant_time_equals
from app.yclients import YClientsClient

DASHBOARD_COOKIE = "admin_key"
DASHBOARD_COOKIE_MAX_AGE = 30 * 24 * 3600

templates = Jinja2Templates(directory=str(Path(__file__).resolve().parent.parent / "templates"))

# device_id приходит от клиента произвольной строкой, и в ссылку он должен
# уходить полностью экранированным. Встроенный urlencode держит "/" за
# безопасный символ и разваливает путь, поэтому safe="".
templates.env.filters["urlpath"] = lambda value: quote(str(value), safe="")

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
    app.state.yclients = yclients_client
    app.state.limiter = RateLimiter()
    app.state.poll_service = poll_service
    app.state.started_at = datetime.now(timezone.utc)

    poll_service.start()
    logger.info("neiro-push started")
    yield
    await poll_service.stop()
    await yclients_client.aclose()
    await fcm_sender.aclose()
    logger.info("neiro-push stopped")


app = FastAPI(title="Neiro Push", version="0.1.0", lifespan=lifespan)
app.include_router(auth.router)
app.include_router(device_events.router)
app.include_router(proxy.router)


def get_database(request: Request) -> Database:
    return request.app.state.db


def get_secret_box(request: Request) -> SecretBox:
    return request.app.state.secret_box


def get_poll_service(request: Request) -> PollService:
    return request.app.state.poll_service


def verify_admin_api_key(
    authorization: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> None:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="missing bearer token"
        )
    token = authorization.removeprefix("Bearer ").strip()
    if not constant_time_equals(token, settings.admin_api_key):
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


@app.post(
    "/v1/admin/devices/{device_id}/revoke",
    dependencies=[Depends(verify_admin_api_key)],
)
async def admin_revoke_device(
    device_id: str,
    db: Database = Depends(get_database),
) -> dict:
    """Отобрать доступ у телефона. До этого сделать это было нечем.

    Строка устройства остаётся — за ней тянется история доставок, по которой
    ещё надо будет понять, куда уходили пуши. Умирает только токен.
    """
    if db.get_device_admin(device_id) is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="device not found")
    revoked = db.revoke_device(device_id)
    logger.warning("device %s revoked via admin API", device_id)
    return {"device_id": device_id, "revoked": revoked}


@app.delete(
    "/v1/admin/devices/{device_id}",
    dependencies=[Depends(verify_admin_api_key)],
)
async def admin_delete_device(
    device_id: str,
    db: Database = Depends(get_database),
) -> dict:
    """Убрать телефон из списка совсем.

    В отличие от `revoke` строка не остаётся: отозванные устройства копятся в
    дашборде и мешают смотреть на живые. Для самого телефона разницы нет —
    токена больше нет, и он попросит войти заново.
    """
    if db.get_device_admin(device_id) is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="device not found")
    deleted = db.delete_device(device_id)
    logger.warning("device %s deleted via admin API", device_id)
    return {"device_id": device_id, "deleted": deleted}


@app.post(
    "/v1/admin/accounts/{account_id}/reset",
    dependencies=[Depends(verify_admin_api_key)],
)
async def admin_reset_account(
    account_id: int,
    db: Database = Depends(get_database),
) -> dict:
    """Потребовать повторный вход паролем.

    Устройства и их токены живы, пуши продолжают идти — приложение просто
    попросит пароль при следующем обращении к прокси.
    """
    if db.get_account(account_id) is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="account not found")
    db.set_reauth_required(account_id, True)
    logger.warning("account %s marked reauth_required via admin API", account_id)
    return {"account_id": account_id, "reauth_required": True}


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


def _dashboard_authenticated(request: Request, settings: Settings) -> bool:
    cookie_value = request.cookies.get(DASHBOARD_COOKIE)
    return bool(cookie_value) and constant_time_equals(cookie_value, settings.admin_api_key)


@app.get("/dashboard", response_class=HTMLResponse)
async def dashboard_page(
    request: Request,
    runs_offset: int = Query(default=0, ge=0),
    deleted: bool = Query(default=False),
    settings: Settings = Depends(get_settings),
    db: Database = Depends(get_database),
    poll_service: PollService = Depends(get_poll_service),
) -> HTMLResponse:
    authenticated = _dashboard_authenticated(request, settings)
    context = {
        "authenticated": authenticated,
        "login_error": False,
        # Пришли сюда редиректом после удаления устройства — сказать об этом.
        "device_deleted": deleted,
    }
    if authenticated:
        data = collect_dashboard_data(
            db,
            poll_service,
            settings,
            request.app.state.started_at,
            runs_offset,
        )
        context.update(build_html_context(data))
    return templates.TemplateResponse(request, "dashboard.html", context)


@app.get("/dashboard/status", response_class=HTMLResponse)
async def dashboard_status_fragment(
    request: Request,
    settings: Settings = Depends(get_settings),
    db: Database = Depends(get_database),
    poll_service: PollService = Depends(get_poll_service),
) -> HTMLResponse:
    """Шапка отдельным куском: страница подтягивает её раз в 10 секунд, чтобы
    live-данные обновлялись, не схлопывая раскрытые аккордеоны."""
    if not _dashboard_authenticated(request, settings):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="not authenticated")
    data = collect_status_data(db, poll_service, settings, request.app.state.started_at)
    return templates.TemplateResponse(request, "_status.html", build_status_context(data))


@app.get("/dashboard/poll-runs", response_class=HTMLResponse)
async def dashboard_poll_runs_fragment(
    request: Request,
    offset: int = Query(default=0, ge=0),
    settings: Settings = Depends(get_settings),
    db: Database = Depends(get_database),
    poll_service: PollService = Depends(get_poll_service),
) -> HTMLResponse:
    if not _dashboard_authenticated(request, settings):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="not authenticated")
    data = collect_dashboard_data(
        db,
        poll_service,
        settings,
        request.app.state.started_at,
        offset,
    )
    return templates.TemplateResponse(request, "_poll_runs.html", build_poll_runs_context(data))


@app.get("/dashboard/devices/{device_id}/events", response_class=HTMLResponse)
async def dashboard_device_events_fragment(
    device_id: str,
    request: Request,
    settings: Settings = Depends(get_settings),
    db: Database = Depends(get_database),
) -> HTMLResponse:
    """Уведомления устройства подгружаются только при раскрытии — иначе стартовая
    страница тянула бы историю по всем устройствам сразу."""
    if not _dashboard_authenticated(request, settings):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="not authenticated")
    if db.get_device_admin(device_id) is None:
        raise HTTPException(status_code=404, detail="device not found")
    deliveries = db.list_deliveries_for_device(device_id, DEVICE_EVENTS_LIMIT)
    context = build_device_events_context(device_id, deliveries)
    return templates.TemplateResponse(request, "_device_events.html", context)


@app.post("/dashboard", response_class=HTMLResponse)
@app.post("/dashboard/login", response_class=HTMLResponse)
async def dashboard_login(
    request: Request,
    key: str = Form(...),
    settings: Settings = Depends(get_settings),
    db: Database = Depends(get_database),
    poll_service: PollService = Depends(get_poll_service),
) -> Response:
    if not constant_time_equals(key, settings.admin_api_key):
        context = {"authenticated": False, "login_error": True}
        return templates.TemplateResponse(
            request, "dashboard.html", context, status_code=status.HTTP_401_UNAUTHORIZED
        )
    # Страница отдаётся сразу, без Location-редиректа. У второго поколения это
    # было обязательно: nginx срезал префикс /v2, и абсолютный "/dashboard" в
    # Location уводил браузер мимо сервиса. Здесь префикс не срезается
    # (DEPLOY.md § Публичный маршрут), но редирект всё равно не нужен — а так
    # дашборд переживёт и возможную смену маршрута.
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


def _render_device_page(
    request: Request,
    device_id: str,
    db: Database,
    **flags,
) -> HTMLResponse:
    device = db.get_device_admin(device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="device not found")
    deliveries = db.list_deliveries_for_device(device_id)
    context = {"authenticated": True}
    context.update(build_device_html_context(device, deliveries))
    context.update(flags)
    return templates.TemplateResponse(request, "dashboard_device.html", context)


@app.post("/dashboard/devices/{device_id}/revoke", response_class=HTMLResponse)
async def dashboard_revoke_device(
    device_id: str,
    request: Request,
    settings: Settings = Depends(get_settings),
    db: Database = Depends(get_database),
) -> HTMLResponse:
    """Кнопка «отозвать устройство». Раньше отобрать доступ у телефона было нечем."""
    if not _dashboard_authenticated(request, settings):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="not authenticated")
    if db.get_device_admin(device_id) is None:
        raise HTTPException(status_code=404, detail="device not found")
    db.revoke_device(device_id)
    logger.warning("device %s revoked from dashboard", device_id)
    return _render_device_page(request, device_id, db, revoked=True)


@app.post("/dashboard/devices/{device_id}/delete", response_class=HTMLResponse)
async def dashboard_delete_device(
    device_id: str,
    request: Request,
    settings: Settings = Depends(get_settings),
    db: Database = Depends(get_database),
) -> Response:
    """Кнопка «удалить устройство»: строка уходит, телефон входит заново.

    Возвращаться на страницу устройства после этого некуда — её больше нет,
    поэтому POST/Redirect/GET на список. Путь относительный, как у форм в
    шаблонах: от `dashboard/devices/{id}/delete` три уровня вверх дают
    `dashboard` и под префиксом вроде `/v2`, и без него.
    """
    if not _dashboard_authenticated(request, settings):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="not authenticated")
    device = db.get_device_admin(device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="device not found")
    db.delete_device(device_id)
    logger.warning("device %s deleted from dashboard", device_id)
    return RedirectResponse(
        url="../../../dashboard?deleted=1", status_code=status.HTTP_303_SEE_OTHER
    )


@app.post("/dashboard/devices/{device_id}/reset-account", response_class=HTMLResponse)
async def dashboard_reset_account(
    device_id: str,
    request: Request,
    settings: Settings = Depends(get_settings),
    db: Database = Depends(get_database),
) -> HTMLResponse:
    """Кнопка «сбросить аккаунт»: устройства живы, но потребуется пароль."""
    if not _dashboard_authenticated(request, settings):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="not authenticated")
    device = db.get_device_admin(device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="device not found")
    db.set_reauth_required(int(device["account_id"]), True)
    logger.warning("account %s marked reauth_required from dashboard", device["account_id"])
    return _render_device_page(request, device_id, db, reset_done=True)


@app.get("/dashboard/devices/{device_id}", response_class=HTMLResponse)
async def dashboard_device_page(
    device_id: str,
    request: Request,
    settings: Settings = Depends(get_settings),
    db: Database = Depends(get_database),
) -> HTMLResponse:
    if not _dashboard_authenticated(request, settings):
        context = {"authenticated": False, "login_error": False}
        return templates.TemplateResponse(request, "dashboard.html", context)
    device = db.get_device_admin(device_id)
    if device is None:
        raise HTTPException(status_code=404, detail="device not found")
    deliveries = db.list_deliveries_for_device(device_id)
    context = {"authenticated": True}
    context.update(build_device_html_context(device, deliveries))
    return templates.TemplateResponse(request, "dashboard_device.html", context)
