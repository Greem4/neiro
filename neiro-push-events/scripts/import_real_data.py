"""Импорт выгрузки реального аккаунта с Pi в локальную базу.

Вызывается из scripts/pull-real-data.sh, руками запускать не нужно.

Данные кладутся рядом с тестовыми из seed_dev_data.py: в локальной базе
получаются две компании — синтетическая и настоящая. Это же даёт случай
«компаний больше одной», на котором проверяется поведение дашборда.

Токены не переносятся: YClients-токены с Pi зашифрованы боевым ключом, а
FCM-токен устройства настоящий, и локальный запуск мог бы отправить пуш на живой
телефон. Вместо них — заглушки.
"""

from __future__ import annotations

import json
import os
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.config import get_settings
from app.security import SecretBox

DUMP_PATH = Path(os.environ.get("DUMP_PATH", "data/_real_dump.json"))
FAKE_FCM_TOKEN = "x" * 32


def main() -> int:
    if not DUMP_PATH.is_file():
        print(f"Нет выгрузки: {DUMP_PATH}", file=sys.stderr)
        return 1

    dump = json.loads(DUMP_PATH.read_text(encoding="utf-8"))
    company_id = dump["company_id"]
    if not dump["accounts"]:
        print(f"В выгрузке нет аккаунтов компании {company_id}", file=sys.stderr)
        return 1

    settings = get_settings()
    secret_box = SecretBox(settings.token_encryption_key)
    conn = sqlite3.connect(settings.database_path)
    conn.row_factory = sqlite3.Row

    with conn:
        # Повторный запуск не должен плодить дубли: сносим ранее перенесённую
        # компанию целиком, тестовые данные seed_dev_data.py при этом не трогаем.
        old_ids = [
            r["id"] for r in conn.execute(
                "SELECT id FROM accounts WHERE company_id = ?", (company_id,)
            )
        ]
        if old_ids:
            marks = ",".join("?" for _ in old_ids)
            old_events = [
                r["id"] for r in conn.execute(
                    f"SELECT id FROM events WHERE account_id IN ({marks})", old_ids
                )
            ]
            if old_events:
                emarks = ",".join("?" for _ in old_events)
                conn.execute(
                    f"DELETE FROM push_deliveries WHERE event_id IN ({emarks})", old_events
                )
            conn.execute(f"DELETE FROM events WHERE account_id IN ({marks})", old_ids)
            conn.execute(f"DELETE FROM record_states WHERE account_id IN ({marks})", old_ids)
            conn.execute(f"DELETE FROM devices WHERE account_id IN ({marks})", old_ids)
            conn.execute(f"DELETE FROM accounts WHERE id IN ({marks})", old_ids)
        conn.execute("DELETE FROM poll_runs WHERE company_id = ?", (company_id,))

        account_map: dict[int, int] = {}
        for account in dump["accounts"]:
            cursor = conn.execute(
                """
                INSERT INTO accounts (
                    company_id, staff_id, partner_token_enc, user_token_enc,
                    changed_after, backoff_until, consecutive_errors,
                    last_polled_at, last_error, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    account["company_id"],
                    account["staff_id"],
                    secret_box.encrypt("local-stub-partner-token"),
                    secret_box.encrypt("local-stub-user-token"),
                    account.get("changed_after"),
                    account.get("backoff_until"),
                    account.get("consecutive_errors", 0),
                    account.get("last_polled_at"),
                    account.get("last_error"),
                    account["created_at"],
                    account["updated_at"],
                ),
            )
            account_map[account["id"]] = int(cursor.lastrowid)

        for device in dump["devices"]:
            conn.execute(
                """
                INSERT INTO devices (
                    account_id, device_id, fcm_token, label, app_version,
                    last_ack_event_id, last_seen_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    account_map[device["account_id"]],
                    device["device_id"],
                    FAKE_FCM_TOKEN,
                    device.get("label"),
                    device.get("app_version"),
                    device.get("last_ack_event_id"),
                    device.get("last_seen_at"),
                    device["created_at"],
                    device["updated_at"],
                ),
            )

        event_map: dict[int, int] = {}
        for event in dump["events"]:
            cursor = conn.execute(
                """
                INSERT INTO events (
                    account_id, type, client_name, date, time, kind,
                    prev_date, prev_time, record_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    account_map[event["account_id"]],
                    event["type"],
                    event["client_name"],
                    event["date"],
                    event["time"],
                    event["kind"],
                    event.get("prev_date"),
                    event.get("prev_time"),
                    event.get("record_id"),
                    event["created_at"],
                ),
            )
            event_map[event["id"]] = int(cursor.lastrowid)

        for delivery in dump["deliveries"]:
            new_event_id = event_map.get(delivery["event_id"])
            if new_event_id is None:
                continue
            conn.execute(
                """
                INSERT INTO push_deliveries (event_id, device_id, status, detail, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                (
                    new_event_id,
                    delivery["device_id"],
                    delivery["status"],
                    delivery.get("detail"),
                    delivery["created_at"],
                ),
            )

        for run in dump["poll_runs"]:
            conn.execute(
                """
                INSERT INTO poll_runs (
                    company_id, started_at, duration_ms, records_fetched,
                    events_created, pushes_sent, error
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    run["company_id"],
                    run["started_at"],
                    run["duration_ms"],
                    run["records_fetched"],
                    run["events_created"],
                    run["pushes_sent"],
                    run.get("error"),
                ),
            )

    print(
        f"Перенесена компания {company_id}: "
        f"аккаунтов {len(dump['accounts'])}, устройств {len(dump['devices'])}, "
        f"событий {len(dump['events'])}, доставок {len(dump['deliveries'])}, "
        f"циклов {len(dump['poll_runs'])}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
