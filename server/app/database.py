import json
import sqlite3
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterator


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
    records_fingerprint: str | None


@dataclass(frozen=True)
class RegisteredDevice:
    id: int
    account_id: int
    device_id: str
    fcm_token: str
    label: str | None


class Database:
    def __init__(self, path: str) -> None:
        self.path = path
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._init_schema()

    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self.path)
        conn.row_factory = sqlite3.Row
        try:
            yield conn
            conn.commit()
        finally:
            conn.close()

    def _init_schema(self) -> None:
        with self.connect() as conn:
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS accounts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    company_id INTEGER NOT NULL,
                    staff_id INTEGER NOT NULL,
                    partner_token_enc TEXT NOT NULL,
                    user_token_enc TEXT NOT NULL,
                    changed_after TEXT,
                    records_fingerprint TEXT,
                    last_polled_at TEXT,
                    last_error TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    UNIQUE(company_id, staff_id)
                );

                CREATE TABLE IF NOT EXISTS devices (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    account_id INTEGER NOT NULL,
                    device_id TEXT NOT NULL,
                    fcm_token TEXT NOT NULL,
                    label TEXT,
                    app_version TEXT,
                    last_seen_at TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    UNIQUE(device_id),
                    FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
                );

                CREATE INDEX IF NOT EXISTS idx_devices_account_id
                    ON devices(account_id);
                CREATE INDEX IF NOT EXISTS idx_devices_fcm_token
                    ON devices(fcm_token);
                """
            )

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

    def list_accounts(self) -> list[WatchedAccount]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT id, company_id, staff_id, partner_token_enc, user_token_enc,
                       changed_after, records_fingerprint
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
                records_fingerprint=row["records_fingerprint"],
            )
            for row in rows
        ]

    def list_devices_for_account(self, account_id: int) -> list[RegisteredDevice]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT id, account_id, device_id, fcm_token, label
                FROM devices
                WHERE account_id = ?
                ORDER BY id
                """,
                (account_id,),
            ).fetchall()
        return [
            RegisteredDevice(
                id=int(row["id"]),
                account_id=int(row["account_id"]),
                device_id=row["device_id"],
                fcm_token=row["fcm_token"],
                label=row["label"],
            )
            for row in rows
        ]

    def update_account_poll_state(
        self,
        account_id: int,
        *,
        changed_after: str | None = None,
        records_fingerprint: str | None = None,
        last_error: str | None = None,
    ) -> None:
        now = utc_now_iso()
        with self.connect() as conn:
            conn.execute(
                """
                UPDATE accounts
                SET changed_after = COALESCE(?, changed_after),
                    records_fingerprint = COALESCE(?, records_fingerprint),
                    last_polled_at = ?,
                    last_error = ?,
                    updated_at = ?
                WHERE id = ?
                """,
                (changed_after, records_fingerprint, now, last_error, now, account_id),
            )

    def stats(self) -> dict[str, int]:
        with self.connect() as conn:
            accounts = conn.execute("SELECT COUNT(*) AS c FROM accounts").fetchone()["c"]
            devices = conn.execute("SELECT COUNT(*) AS c FROM devices").fetchone()["c"]
        return {"accounts": int(accounts), "devices": int(devices)}

    def get_device(self, device_id: str) -> RegisteredDevice | None:
        with self.connect() as conn:
            row = conn.execute(
                """
                SELECT id, account_id, device_id, fcm_token, label
                FROM devices
                WHERE device_id = ?
                """,
                (device_id,),
            ).fetchone()
        if row is None:
            return None
        return RegisteredDevice(
            id=int(row["id"]),
            account_id=int(row["account_id"]),
            device_id=row["device_id"],
            fcm_token=row["fcm_token"],
            label=row["label"],
        )

    def list_all_devices(self) -> list[RegisteredDevice]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT id, account_id, device_id, fcm_token, label
                FROM devices
                ORDER BY id
                """
            ).fetchall()
        return [
            RegisteredDevice(
                id=int(row["id"]),
                account_id=int(row["account_id"]),
                device_id=row["device_id"],
                fcm_token=row["fcm_token"],
                label=row["label"],
            )
            for row in rows
        ]

    def list_devices_admin(self) -> list[dict]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT
                    d.device_id,
                    d.label,
                    d.app_version,
                    d.last_seen_at,
                    d.created_at,
                    a.id AS account_id,
                    a.company_id,
                    a.staff_id,
                    a.last_polled_at,
                    a.last_error
                FROM devices d
                JOIN accounts a ON a.id = d.account_id
                ORDER BY d.id
                """
            ).fetchall()
        return [dict(row) for row in rows]

    def list_accounts_admin(self) -> list[dict]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT
                    a.id,
                    a.company_id,
                    a.staff_id,
                    a.changed_after,
                    a.last_polled_at,
                    a.last_error,
                    a.created_at,
                    a.updated_at,
                    COUNT(d.id) AS device_count
                FROM accounts a
                LEFT JOIN devices d ON d.account_id = a.id
                GROUP BY a.id
                ORDER BY a.id
                """
            ).fetchall()
        return [dict(row) for row in rows]
