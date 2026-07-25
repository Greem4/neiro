import sqlite3
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterator, Protocol


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


@dataclass(frozen=True)
class WatchedAccount:
    id: int
    company_id: int
    staff_id: int
    partner_token_enc: str
    user_token_enc: str
    changed_after: str | None
    backoff_until: str | None
    consecutive_errors: int


@dataclass(frozen=True)
class RegisteredDevice:
    id: int
    account_id: int
    device_id: str
    fcm_token: str
    label: str | None
    app_version: str | None
    last_ack_event_id: int | None


@dataclass(frozen=True)
class RecordState:
    record_id: int
    date: str
    time: str
    attendance: int
    deleted: int
    client_name: str
    kind: str


class EventLike(Protocol):
    """Структурный контракт для вставки — под него подходит `events.DerivedEvent`."""

    type: str
    client_name: str
    date: str
    time: str
    kind: str
    prev_date: str | None
    prev_time: str | None
    record_id: int | None


@dataclass(frozen=True)
class Event:
    id: int
    account_id: int
    type: str
    client_name: str
    date: str
    time: str
    kind: str
    prev_date: str | None
    prev_time: str | None
    record_id: int | None
    created_at: str


SCHEMA = """
CREATE TABLE IF NOT EXISTS accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    company_id INTEGER NOT NULL,
    staff_id INTEGER NOT NULL,
    partner_token_enc TEXT NOT NULL,
    user_token_enc TEXT NOT NULL,
    changed_after TEXT,
    backoff_until TEXT,
    consecutive_errors INTEGER NOT NULL DEFAULT 0,
    last_polled_at TEXT,
    last_error TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(company_id, staff_id)
);

CREATE TABLE IF NOT EXISTS devices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    device_id TEXT NOT NULL UNIQUE,
    fcm_token TEXT NOT NULL,
    label TEXT,
    app_version TEXT,
    last_ack_event_id INTEGER,
    last_seen_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS record_states (
    account_id INTEGER NOT NULL,
    record_id INTEGER NOT NULL,
    date TEXT NOT NULL,
    time TEXT NOT NULL,
    attendance INTEGER NOT NULL,
    deleted INTEGER NOT NULL,
    client_name TEXT NOT NULL,
    kind TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (account_id, record_id),
    FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    type TEXT NOT NULL,
    client_name TEXT NOT NULL,
    date TEXT NOT NULL,
    time TEXT NOT NULL,
    kind TEXT NOT NULL,
    prev_date TEXT,
    prev_time TEXT,
    record_id INTEGER,
    created_at TEXT NOT NULL,
    FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_events_account ON events(account_id, id);

CREATE TABLE IF NOT EXISTS push_deliveries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id INTEGER NOT NULL,
    device_id TEXT NOT NULL,
    status TEXT NOT NULL,
    detail TEXT,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_deliveries_event ON push_deliveries(event_id);

CREATE TABLE IF NOT EXISTS poll_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    company_id INTEGER NOT NULL,
    started_at TEXT NOT NULL,
    duration_ms INTEGER NOT NULL,
    records_fetched INTEGER NOT NULL,
    events_created INTEGER NOT NULL,
    pushes_sent INTEGER NOT NULL,
    error TEXT
);
CREATE INDEX IF NOT EXISTS idx_poll_runs_started ON poll_runs(started_at);
"""


class Database:
    def __init__(self, path: str) -> None:
        self.path = path
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._init_schema()

    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self.path)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA busy_timeout=5000")
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def _init_schema(self) -> None:
        with self.connect() as conn:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA synchronous=NORMAL")
            conn.executescript(SCHEMA)

    def upsert_account(
        self,
        company_id: int,
        staff_id: int,
        partner_token_enc: str,
        user_token_enc: str,
    ) -> int:
        now = utc_now_iso()
        with self.connect() as conn:
            conn.execute(
                """
                INSERT INTO accounts (
                    company_id, staff_id, partner_token_enc, user_token_enc,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(company_id, staff_id) DO UPDATE SET
                    partner_token_enc = excluded.partner_token_enc,
                    user_token_enc = excluded.user_token_enc,
                    updated_at = excluded.updated_at
                """,
                (company_id, staff_id, partner_token_enc, user_token_enc, now, now),
            )
            row = conn.execute(
                "SELECT id FROM accounts WHERE company_id = ? AND staff_id = ?",
                (company_id, staff_id),
            ).fetchone()
            assert row is not None
            return int(row["id"])

    def upsert_device(
        self,
        account_id: int,
        device_id: str,
        fcm_token: str,
        label: str | None,
        app_version: str | None,
    ) -> None:
        now = utc_now_iso()
        with self.connect() as conn:
            conn.execute(
                """
                INSERT INTO devices (
                    account_id, device_id, fcm_token, label, app_version,
                    last_seen_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(device_id) DO UPDATE SET
                    account_id = excluded.account_id,
                    fcm_token = excluded.fcm_token,
                    label = excluded.label,
                    app_version = excluded.app_version,
                    last_seen_at = excluded.last_seen_at,
                    updated_at = excluded.updated_at
                """,
                (account_id, device_id, fcm_token, label, app_version, now, now, now),
            )

    def delete_device(self, device_id: str) -> bool:
        with self.connect() as conn:
            cursor = conn.execute("DELETE FROM devices WHERE device_id = ?", (device_id,))
            return cursor.rowcount > 0

    def get_device(self, device_id: str) -> RegisteredDevice | None:
        with self.connect() as conn:
            row = conn.execute(
                """
                SELECT id, account_id, device_id, fcm_token, label, app_version,
                       last_ack_event_id
                FROM devices WHERE device_id = ?
                """,
                (device_id,),
            ).fetchone()
        return _row_to_device(row) if row else None

    def list_accounts(self) -> list[WatchedAccount]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT id, company_id, staff_id, partner_token_enc, user_token_enc,
                       changed_after, backoff_until, consecutive_errors
                FROM accounts
                ORDER BY id
                """
            ).fetchall()
        return [
            WatchedAccount(
                id=int(row["id"]),
                company_id=int(row["company_id"]),
                staff_id=int(row["staff_id"]),
                partner_token_enc=row["partner_token_enc"],
                user_token_enc=row["user_token_enc"],
                changed_after=row["changed_after"],
                backoff_until=row["backoff_until"],
                consecutive_errors=int(row["consecutive_errors"]),
            )
            for row in rows
        ]

    def list_devices_for_account(self, account_id: int) -> list[RegisteredDevice]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT id, account_id, device_id, fcm_token, label, app_version,
                       last_ack_event_id
                FROM devices WHERE account_id = ? ORDER BY id
                """,
                (account_id,),
            ).fetchall()
        return [_row_to_device(row) for row in rows]

    def update_account_poll_state(
        self,
        account_id: int,
        *,
        changed_after: str | None = None,
        backoff_until: str | None = None,
        consecutive_errors: int | None = None,
        last_error: str | None = None,
    ) -> None:
        now = utc_now_iso()
        with self.connect() as conn:
            conn.execute(
                """
                UPDATE accounts
                SET changed_after = COALESCE(?, changed_after),
                    backoff_until = ?,
                    consecutive_errors = COALESCE(?, consecutive_errors),
                    last_polled_at = ?,
                    last_error = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                (
                    changed_after,
                    backoff_until,
                    consecutive_errors,
                    now,
                    last_error,
                    now,
                    account_id,
                ),
            )

    def get_record_states(self, account_id: int) -> dict[int, RecordState]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT record_id, date, time, attendance, deleted, client_name, kind
                FROM record_states WHERE account_id = ?
                """,
                (account_id,),
            ).fetchall()
        return {
            int(row["record_id"]): RecordState(
                record_id=int(row["record_id"]),
                date=row["date"],
                time=row["time"],
                attendance=int(row["attendance"]),
                deleted=int(row["deleted"]),
                client_name=row["client_name"],
                kind=row["kind"],
            )
            for row in rows
        }

    def replace_record_states(
        self, account_id: int, states: dict[int, RecordState]
    ) -> None:
        now = utc_now_iso()
        with self.connect() as conn:
            conn.execute("DELETE FROM record_states WHERE account_id = ?", (account_id,))
            conn.executemany(
                """
                INSERT INTO record_states (
                    account_id, record_id, date, time, attendance, deleted,
                    client_name, kind, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                [
                    (
                        account_id,
                        state.record_id,
                        state.date,
                        state.time,
                        state.attendance,
                        state.deleted,
                        state.client_name,
                        state.kind,
                        now,
                    )
                    for state in states.values()
                ],
            )

    def insert_events(self, account_id: int, events: list[EventLike]) -> list[int]:
        now = utc_now_iso()
        ids = []
        with self.connect() as conn:
            for event in events:
                cursor = conn.execute(
                    """
                    INSERT INTO events (
                        account_id, type, client_name, date, time, kind,
                        prev_date, prev_time, record_id, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        account_id,
                        event.type,
                        event.client_name,
                        event.date,
                        event.time,
                        event.kind,
                        event.prev_date,
                        event.prev_time,
                        event.record_id,
                        now,
                    ),
                )
                ids.append(int(cursor.lastrowid))
        return ids

    def list_events_since(
        self, account_id: int, since_id: int, limit: int = 100
    ) -> list[Event]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT id, account_id, type, client_name, date, time, kind,
                       prev_date, prev_time, record_id, created_at
                FROM events
                WHERE account_id = ? AND id > ?
                ORDER BY id
                LIMIT ?
                """,
                (account_id, since_id, limit),
            ).fetchall()
        return [_row_to_event(row) for row in rows]

    def record_push_delivery(
        self, event_id: int, device_id: str, status: str, detail: str | None
    ) -> None:
        now = utc_now_iso()
        with self.connect() as conn:
            conn.execute(
                """
                INSERT INTO push_deliveries (event_id, device_id, status, detail, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                (event_id, device_id, status, detail, now),
            )

    def record_poll_run(
        self,
        company_id: int,
        started_at: str,
        duration_ms: int,
        records_fetched: int,
        events_created: int,
        pushes_sent: int,
        error: str | None,
    ) -> None:
        with self.connect() as conn:
            conn.execute(
                """
                INSERT INTO poll_runs (
                    company_id, started_at, duration_ms, records_fetched,
                    events_created, pushes_sent, error
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    company_id,
                    started_at,
                    duration_ms,
                    records_fetched,
                    events_created,
                    pushes_sent,
                    error,
                ),
            )

    def update_device_ack(self, device_id: str, last_ack_event_id: int) -> None:
        now = utc_now_iso()
        with self.connect() as conn:
            conn.execute(
                """
                UPDATE devices SET last_ack_event_id = ?, updated_at = ?
                WHERE device_id = ?
                """,
                (last_ack_event_id, now, device_id),
            )

    def purge_old_data(self, events_days: int = 30, poll_runs_days: int = 7) -> None:
        with self.connect() as conn:
            conn.execute(
                "DELETE FROM events WHERE created_at < datetime('now', ?)",
                (f"-{events_days} days",),
            )
            conn.execute(
                "DELETE FROM push_deliveries WHERE created_at < datetime('now', ?)",
                (f"-{events_days} days",),
            )
            conn.execute(
                "DELETE FROM poll_runs WHERE started_at < datetime('now', ?)",
                (f"-{poll_runs_days} days",),
            )

    def stats(self) -> dict[str, int]:
        with self.connect() as conn:
            accounts = conn.execute("SELECT COUNT(*) AS c FROM accounts").fetchone()["c"]
            devices = conn.execute("SELECT COUNT(*) AS c FROM devices").fetchone()["c"]
            events_today = conn.execute(
                "SELECT COUNT(*) AS c FROM events WHERE created_at >= datetime('now', '-1 day')"
            ).fetchone()["c"]
        return {
            "accounts": int(accounts),
            "devices": int(devices),
            "events_today": int(events_today),
        }


def _row_to_device(row: sqlite3.Row) -> RegisteredDevice:
    return RegisteredDevice(
        id=int(row["id"]),
        account_id=int(row["account_id"]),
        device_id=row["device_id"],
        fcm_token=row["fcm_token"],
        label=row["label"],
        app_version=row["app_version"],
        last_ack_event_id=row["last_ack_event_id"],
    )


def _row_to_event(row: sqlite3.Row) -> Event:
    return Event(
        id=int(row["id"]),
        account_id=int(row["account_id"]),
        type=row["type"],
        client_name=row["client_name"],
        date=row["date"],
        time=row["time"],
        kind=row["kind"],
        prev_date=row["prev_date"],
        prev_time=row["prev_time"],
        record_id=row["record_id"],
        created_at=row["created_at"],
    )
