from __future__ import annotations

import logging

from app.database import Database, RegisteredDevice
from app.fcm import FcmSender

logger = logging.getLogger(__name__)


async def send_test_push(
    *,
    database: Database,
    fcm: FcmSender,
    device_id: str | None = None,
    account_id: int | None = None,
) -> dict:
    if not fcm.is_configured:
        raise RuntimeError("FCM is not configured")

    devices = _resolve_devices(database, device_id=device_id, account_id=account_id)
    if not devices:
        return {"sent": 0, "failed": 0, "removed": 0, "targets": []}

    sent = 0
    failed = 0
    removed = 0
    targets: list[dict] = []

    accounts = {account.id: account for account in database.list_accounts()}

    for device in devices:
        account = accounts.get(device.account_id)
        if account is None:
            failed += 1
            targets.append(
                {
                    "device_id": device.device_id,
                    "label": device.label,
                    "status": "error",
                    "detail": "account not found",
                }
            )
            continue

        try:
            result = await fcm.send_sync_push(
                token=device.fcm_token,
                company_id=account.company_id,
                staff_id=account.staff_id,
                reason="test_push",
            )
            if result.token_invalid:
                database.delete_device(device.device_id)
                removed += 1
                targets.append(
                    {
                        "device_id": device.device_id,
                        "label": device.label,
                        "status": "removed",
                        "detail": "invalid fcm token",
                    }
                )
                continue
            sent += 1
            targets.append(
                {
                    "device_id": device.device_id,
                    "label": device.label,
                    "status": "sent",
                }
            )
        except Exception as exc:
            failed += 1
            logger.warning("test push failed for %s: %s", device.device_id, exc)
            targets.append(
                {
                    "device_id": device.device_id,
                    "label": device.label,
                    "status": "error",
                    "detail": str(exc)[:200],
                }
            )

    return {
        "sent": sent,
        "failed": failed,
        "removed": removed,
        "targets": targets,
    }


def _resolve_devices(
    database: Database,
    *,
    device_id: str | None,
    account_id: int | None,
) -> list[RegisteredDevice]:
    if device_id:
        device = database.get_device(device_id)
        return [device] if device is not None else []
    if account_id is not None:
        return database.list_devices_for_account(account_id)
    return database.list_all_devices()
