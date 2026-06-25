from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

import httpx

from app.config import Settings


@dataclass(frozen=True)
class YClientsRecord:
    record_id: int
    staff_id: int
    date: str
    datetime: str | None
    attendance: int
    deleted: bool
    client_name: str
    last_change_date: str | None


class YClientsClient:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings

    async def fetch_records(
        self,
        *,
        company_id: int,
        staff_id: int,
        partner_token: str,
        user_token: str,
        changed_after: str | None,
    ) -> list[YClientsRecord]:
        start_date, end_date = self._date_range()
        headers = {
            "Accept": self._settings.yclients_accept,
            "Content-Type": "application/json",
            "Authorization": f"Bearer {partner_token}, User {user_token}",
        }

        all_records: list[YClientsRecord] = []
        page = 1
        page_size = 100

        async with httpx.AsyncClient(
            base_url=self._settings.yclients_base_url,
            timeout=30.0,
        ) as client:
            while page <= 20:
                params: dict[str, Any] = {
                    "start_date": start_date,
                    "end_date": end_date,
                    "staff_id": staff_id,
                    "page": page,
                    "count": page_size,
                }
                if changed_after:
                    params["changed_after"] = changed_after
                    params["with_deleted"] = 1

                response = await client.get(
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
                    if int(raw.get("staff_id") or 0) != staff_id:
                        continue
                    all_records.append(self._parse_record(raw))

                if len(page_data) < page_size:
                    break
                page += 1

        return all_records

    def fingerprint(self, records: list[YClientsRecord]) -> str:
        normalized = sorted(
            (
                record.record_id,
                record.staff_id,
                record.date,
                record.datetime or "",
                record.attendance,
                int(record.deleted),
                record.client_name,
                record.last_change_date or "",
            )
            for record in records
        )
        payload = json.dumps(normalized, separators=(",", ":"), ensure_ascii=False)
        return hashlib.sha256(payload.encode("utf-8")).hexdigest()

    def next_changed_after(self, records: list[YClientsRecord]) -> str | None:
        latest: datetime | None = None
        zone = ZoneInfo("Europe/Moscow")
        for record in records:
            candidate = record.last_change_date or record.datetime
            if not candidate:
                continue
            parsed = self._parse_timestamp(candidate, zone)
            if parsed and (latest is None or parsed > latest):
                latest = parsed
        if latest is None:
            return None
        # Небольшое перекрытие, как в Android [YClientsLiveSyncFormat].
        overlap = latest - timedelta(seconds=5)
        return overlap.astimezone(zone).strftime("%Y-%m-%d %H:%M:%S")

    def _date_range(self) -> tuple[str, str]:
        zone = ZoneInfo("Europe/Moscow")
        today = datetime.now(zone).date()
        end = today + timedelta(days=self._settings.horizon_days)
        return today.isoformat(), end.isoformat()

    @staticmethod
    def _parse_record(raw: dict[str, Any]) -> YClientsRecord:
        client = raw.get("client") or {}
        name = (
            client.get("display_name")
            or " ".join(
                part
                for part in (
                    client.get("surname"),
                    client.get("name"),
                    client.get("patronymic"),
                )
                if part
            ).strip()
            or client.get("name")
            or ""
        )
        return YClientsRecord(
            record_id=int(raw["id"]),
            staff_id=int(raw.get("staff_id") or 0),
            date=str(raw.get("date") or ""),
            datetime=raw.get("datetime"),
            attendance=int(raw.get("attendance") or 0),
            deleted=bool(raw.get("deleted")),
            client_name=str(name).strip(),
            last_change_date=raw.get("last_change_date"),
        )

    @staticmethod
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
