"""Наполняет локальную dev-базу тестовыми данными для дашборда.

Только для scripts/dev.sh — реальный YClients/FCM не трогает.
"""

from __future__ import annotations

import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.config import get_settings
from app.database import Database
from app.security import SecretBox


@dataclass
class _Event:
    type: str
    client_name: str
    date: str
    time: str
    kind: str
    prev_date: str | None = None
    prev_time: str | None = None
    record_id: int | None = None


def main() -> None:
    settings = get_settings()
    db = Database(settings.database_path)
    secret_box = SecretBox(settings.token_encryption_key)

    account1 = db.upsert_account(
        company_id=111111,
        staff_id=222,
        partner_token_enc=secret_box.encrypt("dev-partner-token"),
        user_token_enc=secret_box.encrypt("dev-user-token"),
    )
    account2 = db.upsert_account(
        company_id=333333,
        staff_id=444,
        partner_token_enc=secret_box.encrypt("dev-partner-token-2"),
        user_token_enc=secret_box.encrypt("dev-user-token-2"),
    )

    db.upsert_device(
        account_id=account1,
        device_id="dev-device-1",
        fcm_token="x" * 32,
        label="Pixel 8 (Мария)",
        app_version="0.7.0.0-debug",
    )
    db.upsert_device(
        account_id=account1,
        device_id="dev-device-2",
        fcm_token="x" * 32,
        label="Galaxy S22 (Ольга)",
        app_version="0.6.9.0",
    )
    db.upsert_device(
        account_id=account2,
        device_id="dev-device-3",
        fcm_token="x" * 32,
        label=None,
        app_version="0.7.0.0-debug",
    )

    today = datetime.now(timezone.utc).date()
    tomorrow = (today + timedelta(days=1)).isoformat()
    day_after = (today + timedelta(days=2)).isoformat()

    events = [
        _Event("NEW_BOOKING", "Иванова Соня, 6 ЛЕТ", tomorrow, "10:00", "LESSON", record_id=1),
        _Event("CANCELLED", "Петров Костя, 5 ЛЕТ", tomorrow, "11:30", "LESSON", record_id=2),
        _Event(
            "MOVED",
            "Смирнова Аня, 7 ЛЕТ",
            day_after,
            "09:00",
            "LESSON",
            prev_date=tomorrow,
            prev_time="15:00",
            record_id=3,
        ),
        _Event("CONFIRMED", "Абросимова Полина, 5 ЛЕТ", tomorrow, "12:00", "LESSON", record_id=4),
    ]
    event_ids = db.insert_events(account1, events)

    db.record_push_delivery(event_ids[0], "dev-device-1", "sent", None)
    db.record_push_delivery(event_ids[0], "dev-device-2", "sent", None)
    db.record_push_delivery(event_ids[1], "dev-device-1", "sent", None)
    db.record_push_delivery(event_ids[1], "dev-device-2", "failed", "device unregistered")
    db.record_push_delivery(event_ids[2], "dev-device-1", "sent", None)
    db.record_push_delivery(event_ids[3], "dev-device-1", "sent", None)

    now = datetime.now(timezone.utc)
    db.record_poll_run(111111, (now - timedelta(minutes=5)).isoformat(), 340, 62, 2, 3, None)
    db.record_poll_run(111111, (now - timedelta(minutes=10)).isoformat(), 280, 60, 0, 0, None)
    db.record_poll_run(333333, (now - timedelta(minutes=6)).isoformat(), 150, 10, 0, 0, "YClients timeout")

    db.update_account_poll_state(account1, last_error=None)
    db.update_account_poll_state(account2, last_error="YClients timeout", consecutive_errors=1)

    print(f"Тестовые данные добавлены в {settings.database_path}")


if __name__ == "__main__":
    main()
