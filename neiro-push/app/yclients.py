from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any
from zoneinfo import ZoneInfo

import httpx

from app.config import Settings

logger = logging.getLogger(__name__)


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

    def _partner_headers(self) -> dict[str, str]:
        return {
            "Accept": self._settings.yclients_accept,
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self._settings.yclients_partner_token}",
        }

    async def login(self, login: str, password: str) -> dict[str, Any]:
        """Вход в YClients партнёрским токеном сервиса.

        Пароль здесь и заканчивается: дальше живёт только выданный `user_token`.
        Ни пароль, ни тело запроса не попадают в лог и в текст исключения —
        RISKS.md § Пароль проходит через свой сервер.
        """
        try:
            response = await self._client.post(
                "/auth",
                headers=self._partner_headers(),
                json={"login": login, "password": password},
            )
        except httpx.HTTPError as exc:
            # Только тип исключения: у httpx в str() уезжает URL запроса.
            raise YClientsUpstreamError(f"auth request failed: {type(exc).__name__}") from None

        if response.status_code in (401, 403):
            raise YClientsAuthError("invalid credentials")
        if response.status_code >= 500:
            raise YClientsUpstreamError(f"auth returned {response.status_code}")

        try:
            payload = response.json()
        except ValueError:
            raise YClientsUpstreamError("auth returned non-JSON body") from None

        data = payload.get("data") if payload.get("success") else None
        if not data or not data.get("user_token"):
            # YClients на неверный пароль отвечает 200 с success=false — это
            # отказ, а не сбой: пользователю надо показать «неверный пароль».
            raise YClientsAuthError("invalid credentials")
        return data

    async def fetch_raw(
        self,
        path: str,
        *,
        user_token: str,
        params: dict[str, Any] | None = None,
    ) -> httpx.Response:
        """Сырой GET к YClients: ответ отдаётся вызывающему как есть.

        Тело не разбирается и не переупаковывается — прокси обязан вернуть
        приложению байты YClients вместе с его кодом. Иначе пришлось бы
        переписывать `YClientsModels` и `SessionParser` на телефоне
        (ARCHITECTURE.md § Прокси).
        """
        headers = {
            "Accept": self._settings.yclients_accept,
            "Content-Type": "application/json",
            "Authorization": (
                f"Bearer {self._settings.yclients_partner_token}, User {user_token}"
            ),
        }
        clean = {k: v for k, v in (params or {}).items() if v is not None}
        return await self._client.get(path, headers=headers, params=clean)

    async def fetch_staff(self, company_id: int) -> list[dict[str, Any]]:
        """Публичный `/book_staff`: ему хватает партнёрского токена.

        Приватные эндпоинты сотрудников отдают 403, если у пользователя нет
        админских прав, — поэтому карточки берутся именно отсюда.
        """
        try:
            response = await self._client.get(
                f"/book_staff/{company_id}",
                headers=self._partner_headers(),
            )
        except httpx.HTTPError as exc:
            raise YClientsUpstreamError(f"staff request failed: {type(exc).__name__}") from None

        if response.status_code >= 500:
            raise YClientsUpstreamError(f"book_staff returned {response.status_code}")
        try:
            payload = response.json()
        except ValueError:
            raise YClientsUpstreamError("book_staff returned non-JSON body") from None
        if not payload.get("success"):
            raise YClientsUpstreamError("book_staff returned success=false")
        return list(payload.get("data") or [])

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
        else:
            logger.warning(
                "records truncated company=%s: hit max_pages=%s, fetched=%s",
                company_id, max_pages, len(all_records),
            )

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

    Фолбэка на `datetime` (как в старом сервисе) намеренно нет: это время
    визита, а не время изменения — по нему курсор перескочит вперёд.
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


# ---------------------------------------------------------------------------
# Вход и подбор сотрудника.
#
# Логика переехала с телефона: раньше этим занимался
# YClientsRepository.detectAndSaveStaffId. Здесь она ровно та же, включая
# эвристику совпадения имени, — иначе один и тот же человек определялся бы
# по-разному в старой и новой сборке.
# ---------------------------------------------------------------------------

# Не меньше двух общих токенов: по одному имени слишком легко зацепить тёзку,
# когда в филиале несколько сотрудников с одинаковым именем.
MIN_NAME_MATCH_SCORE = 2


class YClientsAuthError(Exception):
    """Логин или пароль не подошли — YClients ответил, но отказал."""


class YClientsUpstreamError(Exception):
    """YClients недоступен или ответил не по протоколу."""


def normalize_name_tokens(value: str) -> set[str]:
    """Порт `normalizeNameTokens` из YClientsRepository.kt.

    Токены короче трёх букв отбрасываются: инициалы и предлоги дают ложные
    совпадения, а вклад в осмысленный матч у них нулевой.
    """
    lowered = value.lower().replace("ё", "е")
    parts = re.split(r"[ \t\n\-.,]", lowered)
    return {part.strip() for part in parts if len(part.strip()) >= 3}


def match_staff_id(user_name: str, staff: list[dict[str, Any]]) -> int | None:
    """Сотрудник филиала по имени из ответа `/auth`.

    Форматы намеренно сравниваются нестрого: `/auth` отдаёт «Зеленкина Светлана
    Васильевна», а `/book_staff` хранит «Светлана Зеленкина». Требование «все
    токены обязаны совпасть» здесь не работает — лишнее отчество ломало матч, и
    `staff_id` оставался пустым.

    При равном счёте предпочитаем действующих (`fired == 0`), чтобы не
    зацепиться за карточку уволенного тёзки.
    """
    needle = normalize_name_tokens(user_name or "")
    if not needle:
        return None
    # У пользователя в профиле может быть всего один токен («Светлана») — тогда
    # требование двух совпадений недостижимо, и порог опускается.
    min_score = min(MIN_NAME_MATCH_SCORE, len(needle))

    scored: list[tuple[int, int, int]] = []  # (уволен, -счёт, id) — сортировка по возрастанию
    for member in staff:
        tokens = normalize_name_tokens(str(member.get("name") or ""))
        if not tokens:
            continue
        score = len(tokens & needle)
        if score < min_score:
            continue
        fired = 1 if int(member.get("fired") or 0) != 0 else 0
        scored.append((fired, -score, int(member["id"])))

    if not scored:
        return None
    scored.sort(key=lambda item: (item[0], item[1]))
    return scored[0][2]


def normalize_avatar_url(raw: str | None) -> str | None:
    """Протокол-относительный `//host/...` браузеру понятен, а Coil на телефоне
    такую ссылку не откроет — достраиваем https, как это делал клиент."""
    trimmed = (raw or "").strip()
    if not trimmed:
        return None
    if trimmed.startswith("//"):
        return f"https:{trimmed}"
    return trimmed
