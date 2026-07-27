"""Наполняет локальную dev-базу тестовыми данными для дашборда.

Только для scripts/dev.sh — реальный YClients/FCM не трогает.

Форма данных повторяет боевую: одна компания, десяток мастеров (по аккаунту на
каждого), у мастера одно-два устройства. Типы событий берутся только те, что
реально создаёт app/events.py — иначе дашборд правится под данные, которых в
проде не бывает (тест test_seed_script_uses_only_real_event_types это сторожит).

Скрипт ПЕРЕЗАПИСЫВАЕТ содержимое базы: сначала чистит таблицы, потом сеет заново.
Так повторный запуск даёт тот же результат, а не копит мусор поверх старого.
"""

from __future__ import annotations

import random
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.config import get_settings
from app.database import Database
from app.security import SecretBox

COMPANY_ID = 111111
STAFF_IDS = list(range(201, 211))  # 10 аккаунтов в одной компании
POLL_RUNS = 45  # больше страницы в 20 — чтобы было что листать

# Фиксированное зерно: дев-данные должны быть одинаковыми между запусками,
# иначе каждый пересев меняет картинку на дашборде и правки не с чем сравнить.
RANDOM_SEED = 20260727

# Таблицы чистим от детей к родителям: внешние ключи в SQLite включаются не
# всегда, но порядок всё равно должен быть корректным.
TABLES_TO_CLEAR = (
    "push_deliveries",
    "events",
    "record_states",
    "devices",
    "poll_runs",
    "accounts",
)

PHONES = [
    "Pixel 8", "Pixel 7a", "Galaxy S23", "Galaxy A54", "Redmi Note 12",
    "iPhone 13", "Honor 90", "Poco X5", "Galaxy S22", "Nothing Phone (2)",
]
OWNERS = [
    "Мария", "Ольга", "Ирина", "Светлана", "Анна",
    "Екатерина", "Наталья", "Юлия", "Татьяна", "Елена",
]
APP_VERSIONS = ["0.7.0.0-debug", "0.7.0.0", "0.6.9.0", None]
CLIENTS = [
    "Иванова Соня, 6 ЛЕТ", "Петров Костя, 5 ЛЕТ", "Смирнова Аня, 7 ЛЕТ",
    "Абросимова Полина, 5 ЛЕТ", "Кузнецов Миша, 6 ЛЕТ", "Волкова Даша, 4 ГОДА",
    "Соколов Тимур, 7 ЛЕТ", "Морозова Вера, 5 ЛЕТ", "Лебедев Гоша, 6 ЛЕТ",
    "Новикова Кира, 4 ГОДА", "Егорова Настя, 7 ЛЕТ", "Павлов Рома, 5 ЛЕТ",
]
# Веса под жизнь: новых записей и подтверждений много, удалений мало.
EVENT_TYPES = [
    ("NEW_BOOKING", 32),
    ("CLIENT_CONFIRMED", 24),
    ("CANCELLED", 14),
    ("RESCHEDULED", 14),
    ("CLIENT_ARRIVED", 12),
    ("DELETED", 4),
]


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


def _clear(db: Database) -> None:
    with db.connect() as conn:
        for table in TABLES_TO_CLEAR:
            conn.execute(f"DELETE FROM {table}")
        # Сброс счётчиков AUTOINCREMENT, чтобы id начинались с единицы. На пустой
        # базе таблицы ещё нет — SQLite заводит её при первой вставке.
        exists = conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='sqlite_sequence'"
        ).fetchone()
        if exists:
            conn.execute("DELETE FROM sqlite_sequence")


def _backdate(db: Database, table: str, stamps: dict[int, str]) -> None:
    """Раздвигает created_at по времени.

    insert_events и record_push_delivery всегда ставят «сейчас», и без этого все
    события на дашборде висели бы одной секундой.
    """
    with db.connect() as conn:
        for row_id, stamp in stamps.items():
            conn.execute(f"UPDATE {table} SET created_at = ? WHERE id = ?", (stamp, row_id))


def main() -> None:
    rnd = random.Random(RANDOM_SEED)
    settings = get_settings()
    db = Database(settings.database_path)
    secret_box = SecretBox(settings.token_encryption_key)

    _clear(db)

    now = datetime.now(timezone.utc)
    today = now.date()
    lesson_days = [(today + timedelta(days=d)).isoformat() for d in (0, 1, 2, 3)]
    lesson_times = ["09:00", "10:00", "11:30", "12:00", "15:00", "17:30", "18:00"]

    accounts: list[tuple[int, int, list[str]]] = []  # (staff_id, account_id, device_ids)
    device_serial = 0
    record_serial = 0

    for staff_id in STAFF_IDS:
        account_id = db.upsert_account(
            company_id=COMPANY_ID,
            staff_id=staff_id,
            partner_token_enc=secret_box.encrypt("dev-partner-token"),
            user_token_enc=secret_box.encrypt("dev-user-token"),
        )

        device_ids = []
        for _ in range(rnd.choice([1, 1, 2])):  # чаще одно устройство, иногда два
            device_serial += 1
            device_id = f"dev-device-{device_serial}"
            owner = OWNERS[device_serial % len(OWNERS)]
            phone = PHONES[device_serial % len(PHONES)]
            db.upsert_device(
                account_id=account_id,
                device_id=device_id,
                fcm_token="x" * 32,
                # Одно устройство оставляем без метки: дашборд должен показать
                # device_id вместо названия и не развалиться.
                label=None if device_serial == 3 else f"{phone} ({owner})",
                app_version=APP_VERSIONS[device_serial % len(APP_VERSIONS)],
            )
            device_ids.append(device_id)

        accounts.append((staff_id, account_id, device_ids))

    # Первому аккаунту даём историю побольше: на его устройстве должно быть
    # больше 20 уведомлений, чтобы в аккордеоне появилась ссылка «вся история».
    event_stamps: dict[int, str] = {}
    delivery_stamps: dict[int, str] = {}
    minutes_ago = 0

    for index, (_staff_id, account_id, device_ids) in enumerate(accounts):
        event_count = 26 if index == 0 else rnd.randint(2, 6)
        events = []
        for _ in range(event_count):
            record_serial += 1
            event_type = rnd.choices(
                [t for t, _ in EVENT_TYPES], weights=[w for _, w in EVENT_TYPES]
            )[0]
            date = rnd.choice(lesson_days)
            time = rnd.choice(lesson_times)
            prev_date = prev_time = None
            if event_type == "RESCHEDULED":
                prev_date = rnd.choice(lesson_days)
                prev_time = rnd.choice(lesson_times)
            events.append(
                _Event(
                    type=event_type,
                    client_name=rnd.choice(CLIENTS),
                    date=date,
                    time=time,
                    kind="LESSON",
                    prev_date=prev_date,
                    prev_time=prev_time,
                    record_id=record_serial,
                )
            )

        event_ids = db.insert_events(account_id, events)

        for event_id in event_ids:
            minutes_ago += rnd.randint(3, 14)
            event_stamps[event_id] = (now - timedelta(minutes=minutes_ago)).isoformat()
            for device_id in device_ids:
                # Изредка пуш не уходит: нужно видеть все три статуса доставки.
                roll = rnd.random()
                if roll < 0.06:
                    status, detail = "failed", "FCM error 503: service unavailable"
                elif roll < 0.10:
                    status, detail = "token_invalid", None
                else:
                    status, detail = "sent", None
                db.record_push_delivery(event_id, device_id, status, detail)

    _backdate(db, "events", event_stamps)

    # upsert_device ставит last_seen_at = «сейчас» всем сразу, а курсор оставляет
    # пустым. Разводим по времени и подтягиваем курсор к хвосту журнала аккаунта,
    # иначе все устройства выглядят одинаково и «не забиравшими» события.
    with db.connect() as conn:
        for row in conn.execute(
            "SELECT device_id, account_id FROM devices ORDER BY id"
        ).fetchall():
            last_event = conn.execute(
                "SELECT MAX(id) AS m FROM events WHERE account_id = ?",
                (row["account_id"],),
            ).fetchone()["m"]
            # Часть устройств отстаёт на пару событий — так бывает в жизни.
            cursor = None
            if last_event is not None:
                cursor = max(last_event - rnd.choice([0, 0, 1, 3]), 0)
            conn.execute(
                "UPDATE devices SET last_seen_at = ?, last_ack_event_id = ? WHERE device_id = ?",
                (
                    (now - timedelta(minutes=rnd.randint(1, 240))).isoformat(),
                    cursor,
                    row["device_id"],
                ),
            )

    with db.connect() as conn:
        rows = conn.execute(
            "SELECT id, event_id FROM push_deliveries ORDER BY id"
        ).fetchall()
    for row in rows:
        stamp = event_stamps.get(int(row["event_id"]))
        if stamp:
            delivery_stamps[int(row["id"])] = stamp
    _backdate(db, "push_deliveries", delivery_stamps)

    # Циклы опроса: свежий — успешный, чтобы шапка была зелёной, ошибки глубже.
    for i in range(POLL_RUNS):
        started = now - timedelta(minutes=2 * i)
        error = None
        if i in (7, 23):
            error = "YClients returned success=false"
        elif i == 15:
            error = "all accounts backed off"
        db.record_poll_run(
            COMPANY_ID,
            started.isoformat(),
            rnd.randint(180, 900),
            rnd.randint(40, 90),
            0 if error else rnd.randint(0, 3),
            0 if error else rnd.randint(0, 4),
            error,
        )

    # Состояния аккаунтов: большинство работает, два с ошибкой, один на паузе —
    # чтобы на дашборде было видно и сортировку проблемных наверх, и сводку.
    for staff_id, account_id, _devices in accounts:
        if staff_id == 209:
            db.update_account_poll_state(
                account_id,
                last_error="YClients returned success=false",
                consecutive_errors=1,
            )
        elif staff_id == 210:
            db.update_account_poll_state(
                account_id,
                last_error="Server error '502 Bad Gateway' for url 'https://api.yclients.com'",
                consecutive_errors=2,
            )
        elif staff_id == 208:
            db.update_account_poll_state(
                account_id,
                backoff_until=(now + timedelta(minutes=9)).isoformat(),
                consecutive_errors=26,
                last_error="ReadTimeout",
            )
        else:
            db.update_account_poll_state(account_id, last_error=None)

    devices_total = sum(len(d) for _s, _a, d in accounts)
    print(
        f"База пересеяна: {settings.database_path}\n"
        f"  компания {COMPANY_ID}, аккаунтов {len(accounts)}, "
        f"устройств {devices_total}, событий {record_serial}, циклов {POLL_RUNS}"
    )


if __name__ == "__main__":
    main()
