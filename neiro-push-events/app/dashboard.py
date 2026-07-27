from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone

from app.config import Settings
from app.database import Database
from app.poller import MOSCOW, PollService

EVENTS_LIMIT = 50
POLL_RUNS_LIMIT = 20
# Сколько уведомлений показываем внутри раскрытого устройства. Больше — на
# отдельной странице устройства, чтобы аккордеон не превращался в простыню.
DEVICE_EVENTS_LIMIT = 20


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
    poll_runs_total: int
    poll_runs_offset: int

    @property
    def ok(self) -> bool:
        return self.last_poll_error is None


def collect_dashboard_data(
    db: Database,
    poll_service: PollService,
    settings: Settings,
    started_at: datetime,
    runs_offset: int = 0,
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
        poll_runs=db.list_poll_runs_admin(POLL_RUNS_LIMIT, runs_offset),
        poll_runs_total=db.count_poll_runs(),
        poll_runs_offset=runs_offset,
    )


def _plural(count: int, one: str, few: str, many: str) -> str:
    """Русское склонение: 1 час, 2 часа, 5 часов."""
    if 11 <= count % 100 <= 14:
        return many
    last = count % 10
    if last == 1:
        return one
    if 2 <= last <= 4:
        return few
    return many


def _format_uptime(seconds: int) -> str:
    """Аптайм словами: «2 дня 4 часа», «5 часов 12 минут», «17 секунд».

    Сокращения вида «3д 4ч» читались хуже всего в первые минуты после рестарта:
    «0м» не отличить от «сервис не поднялся», поэтому до минуты показываем секунды.
    """
    days, rem = divmod(seconds, 86400)
    hours, rem = divmod(rem, 3600)
    minutes, secs = divmod(rem, 60)
    if days:
        return (
            f"{days} {_plural(days, 'день', 'дня', 'дней')} "
            f"{hours} {_plural(hours, 'час', 'часа', 'часов')}"
        )
    if hours:
        return (
            f"{hours} {_plural(hours, 'час', 'часа', 'часов')} "
            f"{minutes} {_plural(minutes, 'минута', 'минуты', 'минут')}"
        )
    if minutes:
        return f"{minutes} {_plural(minutes, 'минута', 'минуты', 'минут')}"
    return f"{secs} {_plural(secs, 'секунда', 'секунды', 'секунд')}"


def _hms(value: str | None) -> str:
    """Время на экран — московское.

    В базе всё лежит в UTC (`utc_now_iso`) и таким должно остаться: по нему
    считаются ретеншен и «за сутки». Но смотрит на дашборд человек в МСК, и без
    конверсии часы показывали на три часа назад — 08:24 вместо 11:24.
    """
    if not value:
        return "—"
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError:
        return value
    if parsed.tzinfo is None:
        # Подстраховка: naive-строка (формат SQLite datetime('now')) — это UTC.
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(MOSCOW).strftime("%H:%M:%S")


def _short_date(value: str) -> str:
    try:
        return datetime.strptime(value, "%Y-%m-%d").strftime("%d.%m")
    except ValueError:
        return value


def _account_state(account: dict) -> tuple[str, str]:
    """(машинный статус, подпись) — статус никогда не передаётся одним цветом,
    рядом всегда идёт текст."""
    if account["backoff_until"]:
        return "critical", f"пауза после {account['consecutive_errors']} ошибок"
    if account["last_error"]:
        return "warning", "ошибка"
    return "good", "работает"


def _format_deliveries(deliveries: list[dict]) -> list[dict]:
    return [
        {
            **d,
            "delivered_at_hms": _hms(d["delivered_at"]),
            "date_short": _short_date(d["date"]),
            "prev_date_short": _short_date(d["prev_date"]) if d["prev_date"] else None,
        }
        for d in deliveries
    ]


def build_status_context(data: DashboardData) -> dict:
    """Шапка дашборда — единственный блок, который сам обновляется раз в 10 секунд."""
    return {
        "data": data,
        "uptime": _format_uptime(data.uptime_seconds),
        "last_polled_at": _hms(data.last_polled_at),
        "last_poll_duration": (
            "—" if data.last_poll_duration_ms is None else f"{data.last_poll_duration_ms} мс"
        ),
    }


def build_poll_runs_context(data: DashboardData) -> dict:
    """Страница циклов опроса: сколько показано, откуда и куда листать."""
    runs = [{**run, "started_at_hms": _hms(run["started_at"])} for run in data.poll_runs]
    offset = data.poll_runs_offset
    return {
        "poll_runs": runs,
        "runs_offset": offset,
        "runs_total": data.poll_runs_total,
        "runs_from": offset + 1 if runs else 0,
        "runs_to": offset + len(runs),
        "runs_prev": max(offset - POLL_RUNS_LIMIT, 0) if offset > 0 else None,
        "runs_next": (
            offset + POLL_RUNS_LIMIT
            if offset + POLL_RUNS_LIMIT < data.poll_runs_total
            else None
        ),
    }


def build_html_context(data: DashboardData) -> dict:
    """Готовит форматированные поля для dashboard.html — шаблон сам ничего не считает."""
    devices = [
        {**device, "last_seen_hms": _hms(device["last_seen_at"])} for device in data.devices
    ]
    accounts = []
    for account in data.accounts:
        state, state_label = _account_state(account)
        account_devices = [d for d in devices if d["account_id"] == account["id"]]
        accounts.append(
            {
                **account,
                "last_polled_hms": _hms(account["last_polled_at"]),
                "state": state,
                "state_label": state_label,
                "devices": account_devices,
            }
        )
    context = {"accounts": accounts}
    context.update(build_status_context(data))
    context.update(build_poll_runs_context(data))
    return context


def build_device_events_context(device_id: str, deliveries: list[dict]) -> dict:
    """Фрагмент со списком уведомлений внутри раскрытого устройства."""
    return {
        "device_id": device_id,
        "deliveries": _format_deliveries(deliveries),
        "has_more": len(deliveries) >= DEVICE_EVENTS_LIMIT,
    }


def build_device_html_context(device: dict, deliveries: list[dict]) -> dict:
    """Готовит форматированные поля для dashboard_device.html — карточка устройства
    и список уведомлений, которые на него пришли (§8.4 плана)."""
    return {
        "device": {**device, "last_seen_hms": _hms(device["last_seen_at"])},
        "deliveries": _format_deliveries(deliveries),
    }


def render_dashboard_text(data: DashboardData) -> str:
    """Тот же дашборд, что и /dashboard, но простым текстом для curl (§8.5)."""
    status = "ok" if data.ok else "error"
    duration = "—" if data.last_poll_duration_ms is None else f"{data.last_poll_duration_ms}мс"
    lines = [
        f"neiro-push-events  ●  {status}        "
        f"аптайм {_format_uptime(data.uptime_seconds)}      "
        f"опрос {data.poll_day_seconds}с (день до {data.quiet_start_hour}:00)      "
        f"время МСК",
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
        # Разделители пробелами, а не только шириной поля: длинное имя клиента
        # («Абросимова Полина, 5 ЛЕТ») иначе слипается с датой занятия.
        lines.append(
            f"{_hms(event['created_at'])}  {event['type']:<17}  {event['client_name']:<17}  "
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
