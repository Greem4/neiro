from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

import httpx

from app.config import Settings


@dataclass(frozen=True)
class YClientsRecord:
    record_id: int
    staff_id: int
    date: str
    time: str
    attendance: int
    deleted: bool
    client_name: str
    kind: str
    last_change_date: str | None


class YClientsClient:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._client = httpx.AsyncClient(
            base_url=settings.yclients_base_url,
            timeout=30.0,
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    async def fetch_company_records(
        self,
        *,
        company_id: int,
        partner_token: str,
        user_token: str,
        changed_after: str | None,
    ) -> list[YClientsRecord]:
        """Один запрос на всю компанию — записи всех специалистов разом.

        Если changed_after is None — сидирование: запрос без фильтра по всему
        горизонту (см. §7 Этап 5, п.3 плана).
        """
        start_date, end_date = self._date_range()
        headers = {
            "Accept": self._settings.yclients_accept,
            "Content-Type": "application/json",
            "Authorization": f"Bearer {partner_token}, User {user_token}",
        }

        all_records: list[YClientsRecord] = []
        page = 1
        page_size = 100
        max_pages = self._settings.yclients_max_pages

        while page <= max_pages:
            params: dict[str, Any] = {
                "start_date": start_date,
                "end_date": end_date,
                "page": page,
                "count": page_size,
            }
            if changed_after:
                params["changed_after"] = changed_after
                params["with_deleted"] = 1

            response = await self._client.get(
                f"/records/{company_id}",
                headers=headers,
                params=params,
            )
            response.raise_for_status()
            payload = response.json()
            if not payload.get("success"):
                raise RuntimeError("YClients returned success=false")

            page_data = payload.get("data") or []
            for raw in page_data:
                all_records.append(self._parse_record(raw))

            if len(page_data) < page_size:
                break
            page += 1

        return all_records

    def _date_range(self) -> tuple[str, str]:
        zone = ZoneInfo("Europe/Moscow")
        today = datetime.now(zone).date()
        end = today + timedelta(days=self._settings.horizon_days)
        return today.isoformat(), end.isoformat()

    @staticmethod
    def _parse_record(raw: dict[str, Any]) -> YClientsRecord:
        client = raw.get("client") or {}
        # Порядок полей обязан совпадать с extractClientName в приложении
        # (YClientsCalendarSync.kt:700) — иначе dedupeKey разъедется и
        # одно событие покажется дважды. См. docs/push-events/app.md §2.2.
        name = (
            client.get("display_name")
            or " ".join(
                part
                for part in (client.get("name"), client.get("surname"))
                if part
            ).strip()
        )
        services = raw.get("services") or []
        is_diagnostics = any(
            "диагностика" in str(service.get("title") or "").lower()
            for service in services
        )
        return YClientsRecord(
            record_id=int(raw["id"]),
            staff_id=int(raw.get("staff_id") or 0),
            date=_extract_date(raw.get("datetime"), raw.get("date")),
            time=_extract_time(raw.get("datetime")),
            attendance=int(raw.get("attendance") or 0),
            deleted=bool(raw.get("deleted")),
            client_name=str(name).strip(),
            kind="DIAGNOSTICS" if is_diagnostics else "LESSON",
            last_change_date=raw.get("last_change_date"),
        )


def next_changed_after(records: list[YClientsRecord]) -> str | None:
    """Курсор для следующего опроса — самый свежий `last_change_date` в пачке.

    Небольшое перекрытие (5 с) страхует от границы окна, как в старом сервисе
    и Android-клиенте.
    """
    zone = ZoneInfo("Europe/Moscow")
    latest: datetime | None = None
    for record in records:
        if not record.last_change_date:
            continue
        parsed = _parse_timestamp(record.last_change_date, zone)
        if parsed and (latest is None or parsed > latest):
            latest = parsed
    if latest is None:
        return None
    overlap = latest - timedelta(seconds=5)
    return overlap.astimezone(zone).strftime("%Y-%m-%d %H:%M:%S")


def _parse_timestamp(value: str, zone: ZoneInfo) -> datetime | None:
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S%z", "%Y-%m-%dT%H:%M:%S"):
        try:
            parsed = datetime.strptime(value, fmt)
            if parsed.tzinfo is None:
                return parsed.replace(tzinfo=zone)
            return parsed
        except ValueError:
            continue
    return None


def _extract_date(datetime_value: str | None, date_value: str | None) -> str:
    """`YYYY-MM-DD` подстрокой, без пересчёта часового пояса (app.md §2.1).

    Основной источник — `datetime` (`2026-06-28T18:00:00+03:00`), тот же, из
    которого берётся `time`: иначе на границе суток дата и время разъедутся.
    Фолбэк — поле `date`, которое YClients отдаёт как `2026-06-28 18:00:00`,
    то есть с временем: от него берутся первые 10 символов.
    """
    for value in (datetime_value, date_value):
        if not value:
            continue
        head = value[:10]
        if len(head) == 10 and head[4] == "-" and head[7] == "-":
            return head
    return ""


def _extract_time(value: str | None) -> str:
    if not value:
        return ""
    try:
        if "T" in value:
            return value.split("T", 1)[1][:5]
        if " " in value:
            return value.split(" ", 1)[1][:5]
    except (IndexError, ValueError):
        return ""
    return ""
