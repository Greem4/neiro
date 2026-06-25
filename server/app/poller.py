from __future__ import annotations

import asyncio
import logging

from app.config import Settings, get_settings
from app.poll_schedule import get_poll_schedule
from app.database import Database, WatchedAccount
from app.fcm import FcmSender
from app.security import SecretBox
from app.yclients import YClientsClient

logger = logging.getLogger(__name__)


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
        self._schedule = get_poll_schedule(
            settings.poll_interval_seconds,
            settings.poll_night_interval_seconds,
        )
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
        for account in accounts:
            await self._poll_account(account)

    async def _run_loop(self) -> None:
        logger.info(
            "poll loop started (day=%ss, night=%ss, MSK quiet 21:00-09:00)",
            self._schedule.day_interval_seconds,
            self._schedule.night_interval_seconds,
        )
        while not self._stop_event.is_set():
            try:
                await self.poll_once()
            except Exception:
                logger.exception("poll loop failed")
            delay = self._schedule.interval_seconds()
            try:
                await asyncio.wait_for(self._stop_event.wait(), timeout=delay)
            except asyncio.TimeoutError:
                continue
        logger.info("poll loop stopped")

    async def _poll_account(self, account: WatchedAccount) -> None:
        devices = self._db.list_devices_for_account(account.id)
        if not devices:
            return

        try:
            partner_token = self._secret_box.decrypt(account.partner_token_enc)
            user_token = self._secret_box.decrypt(account.user_token_enc)
            records = await self._yclients.fetch_records(
                company_id=account.company_id,
                staff_id=account.staff_id,
                partner_token=partner_token,
                user_token=user_token,
                changed_after=account.changed_after,
            )
            fingerprint = self._yclients.fingerprint(records)
            changed = (
                account.records_fingerprint is not None
                and fingerprint != account.records_fingerprint
            )
            is_first_baseline = account.records_fingerprint is None

            next_changed_after = self._yclients.next_changed_after(records)
            if next_changed_after:
                changed_after = next_changed_after
            elif account.changed_after:
                changed_after = account.changed_after
            else:
                changed_after = None

            self._db.update_account_poll_state(
                account.id,
                changed_after=changed_after,
                records_fingerprint=fingerprint,
                last_error=None,
            )

            if is_first_baseline:
                logger.info(
                    "baseline stored for account %s (company=%s staff=%s)",
                    account.id,
                    account.company_id,
                    account.staff_id,
                )
                return

            if not changed:
                return

            if not self._fcm.is_configured:
                self._db.update_account_poll_state(
                    account.id,
                    last_error="calendar changed but FCM is not configured",
                )
                logger.warning(
                    "calendar changed for account %s, but FCM is not configured",
                    account.id,
                )
                return

            reason = "calendar_changed"
            sent = 0
            for device in devices:
                try:
                    await self._fcm.send_sync_push(
                        token=device.fcm_token,
                        company_id=account.company_id,
                        staff_id=account.staff_id,
                        reason=reason,
                    )
                    sent += 1
                except Exception as exc:
                    logger.warning(
                        "failed to push to device %s: %s",
                        device.device_id,
                        exc,
                    )

            logger.info(
                "pushed sync to %s/%s devices for account %s",
                sent,
                len(devices),
                account.id,
            )
        except Exception as exc:
            self._db.update_account_poll_state(
                account.id,
                last_error=str(exc)[:500],
            )
            logger.warning(
                "poll failed for account %s: %s",
                account.id,
                exc,
            )


_poll_service: PollService | None = None


def get_poll_service() -> PollService:
    global _poll_service
    if _poll_service is None:
        settings = get_settings()
        _poll_service = PollService(
            settings=settings,
            database=Database(settings.database_path),
            secret_box=SecretBox(settings.token_encryption_key),
            yclients=YClientsClient(settings),
            fcm=FcmSender(settings),
        )
    return _poll_service
