from __future__ import annotations

import asyncio
import logging
import time
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

from app.config import Settings
from app.database import Database, WatchedAccount, utc_now_iso
from app.events import DerivedEvent, derive_events, merge_states
from app.fcm import FcmSender
from app.security import SecretBox
from app.yclients import YClientsClient, YClientsRecord, next_changed_after

logger = logging.getLogger(__name__)

MOSCOW = ZoneInfo("Europe/Moscow")

# §7 Этап 5, п.6 плана: пауза при ошибке компании, растёт до 15 минут,
# сбрасывается после первого успешного опроса.
BACKOFF_STEPS_SECONDS = (10, 30, 60, 120, 300, 600, 900)


def _backoff_seconds(consecutive_errors: int) -> int:
    index = min(max(consecutive_errors - 1, 0), len(BACKOFF_STEPS_SECONDS) - 1)
    return BACKOFF_STEPS_SECONDS[index]


class PollService:
    def __init__(
        self,
        settings: Settings,
        database: Database,
        secret_box: SecretBox,
        yclients: YClientsClient,
        fcm: FcmSender,
    ) -> None:
        self._settings = settings
        self._db = database
        self._secret_box = secret_box
        self._yclients = yclients
        self._fcm = fcm
        self._task: asyncio.Task[None] | None = None
        self._stop_event = asyncio.Event()

    @property
    def fcm_configured(self) -> bool:
        return self._fcm.is_configured

    def start(self) -> None:
        if self._task is None or self._task.done():
            self._stop_event.clear()
            self._task = asyncio.create_task(self._run_loop())

    async def stop(self) -> None:
        self._stop_event.set()
        if self._task is not None:
            await self._task

    async def poll_once(self) -> None:
        accounts = self._db.list_accounts()
        if not accounts:
            return

        by_company: dict[int, list[WatchedAccount]] = {}
        for account in accounts:
            by_company.setdefault(account.company_id, []).append(account)

        for company_id, company_accounts in by_company.items():
            await self._poll_company(company_id, company_accounts)

        self._db.purge_old_data()

    async def _run_loop(self) -> None:
        logger.info(
            "poll loop started (day=%ss, night=%ss, MSK quiet after %s:00)",
            self._settings.poll_interval_seconds,
            self._settings.poll_night_interval_seconds,
            self._settings.quiet_start_hour,
        )
        while not self._stop_event.is_set():
            try:
                await self.poll_once()
            except Exception:
                logger.exception("poll loop failed")
            delay = self._interval_seconds()
            try:
                await asyncio.wait_for(self._stop_event.wait(), timeout=delay)
            except asyncio.TimeoutError:
                continue
        logger.info("poll loop stopped")

    def _interval_seconds(self) -> int:
        hour = datetime.now(MOSCOW).hour
        if hour >= self._settings.quiet_start_hour:
            return self._settings.poll_night_interval_seconds
        return self._settings.poll_interval_seconds

    async def _poll_company(
        self, company_id: int, accounts: list[WatchedAccount]
    ) -> None:
        now = datetime.now(timezone.utc)
        active_accounts = [a for a in accounts if not self._is_backed_off(a, now)]
        if not active_accounts:
            return

        started_at = utc_now_iso()
        started = time.monotonic()
        seeding = any(not self._db.has_record_states(a.id) for a in active_accounts)
        changed_after = None if seeding else self._company_changed_after(active_accounts)

        try:
            lead_account = active_accounts[0]
            partner_token = self._secret_box.decrypt(lead_account.partner_token_enc)
            user_token = self._secret_box.decrypt(lead_account.user_token_enc)
            records = await self._yclients.fetch_company_records(
                company_id=company_id,
                partner_token=partner_token,
                user_token=user_token,
                changed_after=changed_after,
            )
        except Exception as exc:
            error_message = str(exc)[:500]
            for account in active_accounts:
                next_errors = account.consecutive_errors + 1
                backoff_until = (
                    now + timedelta(seconds=_backoff_seconds(next_errors))
                ).isoformat()
                self._db.update_account_poll_state(
                    account.id,
                    backoff_until=backoff_until,
                    consecutive_errors=next_errors,
                    last_error=error_message,
                )
            duration_ms = int((time.monotonic() - started) * 1000)
            self._db.record_poll_run(
                company_id, started_at, duration_ms, 0, 0, 0, error_message
            )
            logger.warning("poll failed company=%s: %s", company_id, error_message)
            return

        next_cursor = next_changed_after(records)

        events_created = 0
        pushes_sent = 0
        for account in active_accounts:
            account_records = [r for r in records if r.staff_id == account.staff_id]
            created, sent = await self._poll_account(account, account_records, seeding)
            events_created += created
            pushes_sent += sent
            self._db.update_account_poll_state(
                account.id,
                changed_after=next_cursor,
                backoff_until=None,
                consecutive_errors=0,
                last_error=None,
            )

        duration_ms = int((time.monotonic() - started) * 1000)
        self._db.record_poll_run(
            company_id, started_at, duration_ms, len(records), events_created, pushes_sent, None
        )

    async def _poll_account(
        self,
        account: WatchedAccount,
        records: list[YClientsRecord],
        seeding: bool,
    ) -> tuple[int, int]:
        previous_states = self._db.get_record_states(account.id)
        if seeding:
            new_states = merge_states(previous_states, records)
            self._db.commit_poll_result(account.id, [], new_states)
            return 0, 0

        events, new_states = derive_events(previous_states, records)
        event_ids = self._db.commit_poll_result(account.id, events, new_states)

        if not events:
            return 0, 0

        for event, event_id in zip(events, event_ids):
            logger.info(
                "event id=%s %s %s %s %s",
                event_id,
                event.type,
                event.client_name,
                event.date,
                event.time,
            )

        devices = self._db.list_devices_for_account(account.id)
        if not devices:
            return len(events), 0

        last_event_id = max(event_ids)
        payload_events = [
            _event_payload(event, event_id) for event, event_id in zip(events, event_ids)
        ]

        results = await asyncio.gather(
            *(
                self._push_to_device(device.device_id, device.fcm_token, event_ids, payload_events, last_event_id)
                for device in devices
            )
        )
        pushes_sent = sum(1 for sent in results if sent)
        return len(events), pushes_sent

    async def _push_to_device(
        self,
        device_id: str,
        fcm_token: str,
        event_ids: list[int],
        payload_events: list[dict],
        last_event_id: int,
    ) -> bool:
        try:
            result = await self._fcm.send_events_push(
                token=fcm_token,
                events=payload_events,
                last_event_id=last_event_id,
            )
        except Exception as exc:
            detail = str(exc)[:300]
            logger.warning("push failed device=%s: %s", device_id, detail)
            for event_id in event_ids:
                self._db.record_push_delivery(event_id, device_id, "failed", detail)
            return False

        if result.token_invalid:
            for event_id in event_ids:
                self._db.record_push_delivery(event_id, device_id, "token_invalid", None)
            removed = self._db.delete_device(device_id)
            if removed:
                logger.warning("removed stale device %s: invalid FCM token", device_id)
            return False

        status = "nudged" if result.nudged else "sent"
        for event_id in event_ids:
            self._db.record_push_delivery(event_id, device_id, status, None)
        return True

    def _company_changed_after(self, accounts: list[WatchedAccount]) -> str | None:
        changed_afters = [a.changed_after for a in accounts]
        if any(c is None for c in changed_afters):
            return None
        return min(changed_afters)

    @staticmethod
    def _is_backed_off(account: WatchedAccount, now: datetime) -> bool:
        if not account.backoff_until:
            return False
        try:
            backoff_until = datetime.fromisoformat(account.backoff_until)
        except ValueError:
            return False
        return now < backoff_until


def _event_payload(event: DerivedEvent, event_id: int) -> dict:
    payload: dict = {
        "id": event_id,
        "type": event.type,
        "client_name": event.client_name,
        "date": event.date,
        "time": event.time,
        "kind": event.kind,
    }
    if event.prev_date is not None:
        payload["prev_date"] = event.prev_date
    if event.prev_time is not None:
        payload["prev_time"] = event.prev_time
    return payload
