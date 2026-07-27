from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone

from app.config import Settings
from app.database import Database
from app.poller import PollService

EVENTS_LIMIT = 50
POLL_RUNS_LIMIT = 20


@dataclass(frozen=True)
class DashboardData:
    fcm_configured: bool
    uptime_seconds: int
    last_polled_at: str | None
    last_poll_duration_ms: int | None
    last_poll_error: str | None
    errors_today: int
    poll_day_seconds: int
    poll_night_seconds: int
    quiet_start_hour: int
    events: list[dict]
    accounts: list[dict]
    devices: list[dict]
    poll_runs: list[dict]

    @property
    def ok(self) -> bool:
        return self.last_poll_error is None


def collect_dashboard_data(
    db: Database,
    poll_service: PollService,
    settings: Settings,
    started_at: datetime,
) -> DashboardData:
    """Один источник данных для /dashboard и /v1/admin/dashboard.txt (§8.4 плана) —
    чтобы обе версии не могли разъехаться."""
    summary = db.poll_health_summary()
    uptime_seconds = int((datetime.now(timezone.utc) - started_at).total_seconds())
    return DashboardData(
        fcm_configured=poll_service.fcm_configured,
        uptime_seconds=uptime_seconds,
        last_polled_at=summary["last_polled_at"],
        last_poll_duration_ms=summary["last_poll_duration_ms"],
        last_poll_error=summary["last_poll_error"],
        errors_today=summary["errors_today"],
        poll_day_seconds=settings.poll_interval_seconds,
        poll_night_seconds=settings.poll_night_interval_seconds,
        quiet_start_hour=settings.quiet_start_hour,
        events=db.list_recent_events_admin(EVENTS_LIMIT),
        accounts=db.list_accounts_admin(),
        devices=db.list_devices_admin(),
        poll_runs=db.list_poll_runs_admin(POLL_RUNS_LIMIT),
    )


def _format_uptime(seconds: int) -> str:
    days, rem = divmod(seconds, 86400)
    hours, rem = divmod(rem, 3600)
    minutes, _ = divmod(rem, 60)
    if days:
        return f"{days}д {hours}ч"
    if hours:
        return f"{hours}ч {minutes}м"
    return f"{minutes}м"


def _hms(value: str | None) -> str:
    if not value:
        return "—"
    try:
        return datetime.fromisoformat(value).strftime("%H:%M:%S")
    except ValueError:
        return value


def _short_date(value: str) -> str:
    try:
        return datetime.strptime(value, "%Y-%m-%d").strftime("%d.%m")
    except ValueError:
        return value


def build_html_context(data: DashboardData) -> dict:
    """Готовит форматированные поля для dashboard.html — шаблон сам ничего не считает."""
    events = [
        {
            **event,
            "created_at_hms": _hms(event["created_at"]),
            "date_short": _short_date(event["date"]),
            "prev_date_short": _short_date(event["prev_date"]) if event["prev_date"] else None,
        }
        for event in data.events
    ]
    accounts = []
    for account in data.accounts:
        if account["backoff_until"]:
            state = f"backoff (errors={account['consecutive_errors']})"
        elif account["last_error"]:
            state = "error"
        else:
            state = "ok"
        accounts.append(
            {**account, "last_polled_hms": _hms(account["last_polled_at"]), "state": state}
        )
    devices = [
        {**device, "last_seen_hms": _hms(device["last_seen_at"])} for device in data.devices
    ]
    poll_runs = [
        {**run, "started_at_hms": _hms(run["started_at"])} for run in data.poll_runs
    ]
    return {
        "data": data,
        "uptime": _format_uptime(data.uptime_seconds),
        "last_polled_at": _hms(data.last_polled_at),
        "last_poll_duration": (
            "—" if data.last_poll_duration_ms is None else f"{data.last_poll_duration_ms}мс"
        ),
        "events": events,
        "accounts": accounts,
        "devices": devices,
        "poll_runs": poll_runs,
    }


def render_dashboard_text(data: DashboardData) -> str:
    """Тот же дашборд, что и /dashboard, но простым текстом для curl (§8.5)."""
    status = "ok" if data.ok else "error"
    duration = "—" if data.last_poll_duration_ms is None else f"{data.last_poll_duration_ms}мс"
    lines = [
        f"neiro-push-events  ●  {status}        "
        f"аптайм {_format_uptime(data.uptime_seconds)}      "
        f"опрос {data.poll_day_seconds}с (день до {data.quiet_start_hour}:00)",
        f"последний опрос  {_hms(data.last_polled_at)}   "
        f"длительность {duration}   "
        f"ошибок за сутки: {data.errors_today}",
        "",
        f"СОБЫТИЯ (последние {len(data.events)})",
    ]
    for event in data.events:
        prev = ""
        if event.get("prev_date") and event.get("prev_time"):
            prev = f"  (было {_short_date(event['prev_date'])} {event['prev_time']})"
        lines.append(
            f"{_hms(event['created_at'])}  {event['type']:<17}{event['client_name']:<17}"
            f"{_short_date(event['date'])} {event['time']}  "
            f"→{event['delivered']}/{event['targets']}{prev}"
        )

    lines += ["", "АККАУНТЫ"]
    for account in data.accounts:
        if account["backoff_until"]:
            state = f"backoff (errors={account['consecutive_errors']})"
        elif account["last_error"]:
            state = "error"
        else:
            state = "ok"
        lines.append(
            f"company={account['company_id']} staff={account['staff_id']}  "
            f"устройств {account['device_count']}  опрос {_hms(account['last_polled_at'])}  {state}"
        )

    lines += ["", "УСТРОЙСТВА"]
    for device in data.devices:
        label = device["label"] or device["device_id"]
        version = device["app_version"] or "—"
        cursor = device["last_ack_event_id"]
        # Два пробела разделителя: версия вида "0.7.0.0-debug" длиннее поля и
        # иначе слипается со следующим словом.
        lines.append(
            f"{label:<20}{version:<10}  видели {_hms(device['last_seen_at'])}  "
            f"курсор {cursor if cursor is not None else '—'}"
        )

    lines += ["", "ЦИКЛЫ ОПРОСА"]
    for run in data.poll_runs:
        error = f"  ошибка: {run['error']}" if run["error"] else ""
        lines.append(
            f"{_hms(run['started_at'])}  company={run['company_id']}  "
            f"{run['duration_ms']}мс  записей {run['records_fetched']}  "
            f"событий {run['events_created']}  пушей {run['pushes_sent']}{error}"
        )

    return "\n".join(lines) + "\n"
