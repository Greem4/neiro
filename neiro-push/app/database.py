import sqlite3
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Iterator, Protocol


def utc_now_iso() -> str:
    """ISO-8601 UTC: `2026-07-26T12:00:00+00:00`.

    Внимание: SQLite-функция datetime('now') отдаёт другой формат —
    `2026-07-26 12:00:00`, без T и без смещения. Строковые сравнения между
    ними верны только на уровне даты (общий префикс YYYY-MM-DD); на точности
    до часа они разъедутся. Ретеншену этого хватает, более тонким сравнениям —
    нет.
    """
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def utc_iso_days_ago(days: int) -> str:
    """Граница окна в формате [utc_now_iso] — для сравнений с колонками, куда
    пишет именно он. С `datetime('now', '-1 day')` такие сравнения врут: на
    одинаковой дате `T` больше пробела, поэтому в окно попадали все записи за
    предыдущую календарную дату целиком — до суток лишку."""
    return (
        (datetime.now(timezone.utc) - timedelta(days=days))
        .replace(microsecond=0)
        .isoformat()
    )


@dataclass(frozen=True)
class WatchedAccount:
    id: int
    company_id: int
    staff_id: int
    user_token_enc: str
    reauth_required: bool
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
    user_login TEXT,                                  -- подсказка при повторном входе
    user_name TEXT,                                   -- имя из /auth, его же отдаёт /v1/session
    avatar_url TEXT,
    user_token_enc TEXT NOT NULL,                     -- Fernet, ключ в .env
    reauth_required INTEGER NOT NULL DEFAULT 0,       -- user_token протух, нужен пароль
    auth_failures INTEGER NOT NULL DEFAULT 0,         -- подряд идущих 401 от YClients
    last_auth_at TEXT,
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
    token_hash TEXT NOT NULL UNIQUE,                  -- sha256(device_token)
    revoked_at TEXT,
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
    status TEXT NOT NULL,          -- sent | failed | token_invalid
    detail TEXT,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_deliveries_event ON push_deliveries(event_id);
-- По device_id идут лента устройства (ORDER BY id DESC) и счётчик доставок в
-- списке устройств; без индекса оба сканировали таблицу за 30 дней целиком.
CREATE INDEX IF NOT EXISTS idx_deliveries_device ON push_deliveries(device_id, id DESC);

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
-- Частичный индекс под SIGNIFICANT_POLL_RUN: и лента циклов, и счётчик к ней
-- ходят только за значимыми, а пустых в таблице на два порядка больше.
-- Предикат обязан совпадать с SIGNIFICANT_POLL_RUN, иначе SQLite индекс не возьмёт.
CREATE INDEX IF NOT EXISTS idx_poll_runs_significant ON poll_runs(id)
    WHERE (error IS NOT NULL OR events_created > 0 OR pushes_sent > 0);
"""

# Значимый цикл — тот, который что-то изменил или сломался. При опросе раз в
# 10 секунд пустых циклов набегает несколько тысяч в сутки, и в этой ленте не
# видно ни ошибок, ни событий: дашборд по умолчанию показывает только значимые.
SIGNIFICANT_POLL_RUN = "(error IS NOT NULL OR events_created > 0 OR pushes_sent > 0)"


def _record_state_changes(
    previous: dict[int, RecordState], states: dict[int, RecordState]
) -> tuple[list[RecordState], list[int]]:
    """Что из нового снимка реально надо записать: изменившиеся и новые состояния,
    плюс id тех, что из снимка исчезли. `RecordState` — frozen dataclass, так что
    сравнение идёт по значению полей."""
    changed = [
        state for record_id, state in states.items() if previous.get(record_id) != state
    ]
    removed = [record_id for record_id in previous if record_id not in states]
    return changed, removed


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
        # Без этого объявленные в схеме ON DELETE CASCADE декоративны: SQLite
        # проверяет внешние ключи только при включённой прагме, и удаление
        # аккаунта оставило бы за собой devices, record_states и events.
        conn.execute("PRAGMA foreign_keys=ON")
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
            self._add_missing_columns(conn)

    @staticmethod
    def _add_missing_columns(conn: sqlite3.Connection) -> None:
        """Догоняет схему на уже существующей базе.

        `CREATE TABLE IF NOT EXISTS` про новые колонки не знает: таблица есть —
        и он молча ничего не делает. Сервис уже развёрнут, база живёт между
        выкатками, поэтому каждая добавленная колонка должна оказаться здесь,
        иначе после деплоя запросы падают на `no such column`.

        Только добавление колонок с безопасным значением по умолчанию —
        переименования и удаления сюда не годятся и требуют отдельной миграции.
        """
        wanted = {
            "accounts": {
                "user_name": "TEXT",
                "avatar_url": "TEXT",
                "auth_failures": "INTEGER NOT NULL DEFAULT 0",
            },
            "devices": {
                "token_hash": "TEXT",
                "revoked_at": "TEXT",
            },
        }
        for table, columns in wanted.items():
            existing = {row["name"] for row in conn.execute(f"PRAGMA table_info({table})")}
            if not existing:
                continue
            for name, decl in columns.items():
                if name not in existing:
                    conn.execute(f"ALTER TABLE {table} ADD COLUMN {name} {decl}")

    def upsert_account(
        self,
        company_id: int,
        staff_id: int,
        user_token_enc: str,
        user_login: str | None = None,
        user_name: str | None = None,
        avatar_url: str | None = None,
    ) -> int:
        """Вызывается на успешном входе, поэтому здесь же снимается
        `reauth_required`: свежий `user_token` — это ровно то, чего ждал флаг."""
        now = utc_now_iso()
        with self.connect() as conn:
            conn.execute(
                """
                INSERT INTO accounts (
                    company_id, staff_id, user_login, user_name, avatar_url,
                    user_token_enc, reauth_required, last_auth_at,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                ON CONFLICT(company_id, staff_id) DO UPDATE SET
                    user_login = excluded.user_login,
                    user_name = excluded.user_name,
                    avatar_url = excluded.avatar_url,
                    user_token_enc = excluded.user_token_enc,
                    reauth_required = 0,
                    last_auth_at = excluded.last_auth_at,
                    updated_at = excluded.updated_at
                """,
                (
                    company_id, staff_id, user_login, user_name, avatar_url,
                    user_token_enc, now, now, now,
                ),
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
        token_hash: str,
        fcm_token: str,
        label: str | None,
        app_version: str | None,
    ) -> None:
        """Повторный вход с того же `device_id` — обычное дело: старый хэш
        затирается новым (прежний `device_token` с этого момента мёртв), отзыв
        снимается, а `last_ack_event_id` не трогается — иначе телефон потерял бы
        события, накопившиеся за время без связи (API.md § auth/login)."""
        now = utc_now_iso()
        with self.connect() as conn:
            conn.execute(
                """
                INSERT INTO devices (
                    account_id, device_id, token_hash, fcm_token, label, app_version,
                    last_seen_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(device_id) DO UPDATE SET
                    account_id = excluded.account_id,
                    token_hash = excluded.token_hash,
                    revoked_at = NULL,
                    fcm_token = excluded.fcm_token,
                    label = excluded.label,
                    app_version = excluded.app_version,
                    last_seen_at = excluded.last_seen_at,
                    updated_at = excluded.updated_at
                """,
                (
                    account_id, device_id, token_hash, fcm_token, label, app_version,
                    now, now, now,
                ),
            )

    def get_device_by_token_hash(self, token_hash: str) -> RegisteredDevice | None:
        """Отозванные не находятся вовсе: для вызывающего отозванный токен
        неотличим от несуществующего, и оба дают один 401."""
        with self.connect() as conn:
            row = conn.execute(
                """
                SELECT id, account_id, device_id, fcm_token, label, app_version,
                       last_ack_event_id
                FROM devices
                WHERE token_hash = ? AND revoked_at IS NULL
                """,
                (token_hash,),
            ).fetchone()
        return _row_to_device(row) if row else None

    def revoke_device(self, device_id: str) -> bool:
        """Выход и отзыв из дашборда: строка остаётся, токен умирает.

        Не `DELETE`: за устройством тянется история доставок, и по нему ещё
        надо будет понять, куда уходили пуши.
        """
        now = utc_now_iso()
        with self.connect() as conn:
            cursor = conn.execute(
                "UPDATE devices SET revoked_at = ?, updated_at = ? "
                "WHERE device_id = ? AND revoked_at IS NULL",
                (now, now, device_id),
            )
            return cursor.rowcount > 0

    def update_device_fcm(self, device_id: str, fcm_token: str) -> None:
        now = utc_now_iso()
        with self.connect() as conn:
            conn.execute(
                "UPDATE devices SET fcm_token = ?, last_seen_at = ?, updated_at = ? "
                "WHERE device_id = ?",
                (fcm_token, now, now, device_id),
            )

    def touch_device_seen(self, device_id: str) -> None:
        now = utc_now_iso()
        with self.connect() as conn:
            conn.execute(
                "UPDATE devices SET last_seen_at = ? WHERE device_id = ?", (now, device_id)
            )

    def set_reauth_required(self, account_id: int, required: bool) -> None:
        now = utc_now_iso()
        with self.connect() as conn:
            conn.execute(
                "UPDATE accounts SET reauth_required = ?, updated_at = ? WHERE id = ?",
                (1 if required else 0, now, account_id),
            )

    def note_upstream_auth_failure(self, account_id: int, threshold: int = 3) -> bool:
        """YClients ответил 401. Возвращает True, если это взвело `reauth_required`.

        По первому же 401 флаг не ставим: авария на стороне YClients разлогинила
        бы всех разом, а пользователь ничего исправить не может. Три подряд —
        уже похоже на настоящий протухший токен
        (RISKS.md § Протухший user_token).
        """
        now = utc_now_iso()
        with self.connect() as conn:
            conn.execute(
                "UPDATE accounts SET auth_failures = auth_failures + 1, updated_at = ? WHERE id = ?",
                (now, account_id),
            )
            row = conn.execute(
                "SELECT auth_failures FROM accounts WHERE id = ?", (account_id,)
            ).fetchone()
            failures = int(row["auth_failures"]) if row else 0
            if failures < threshold:
                return False
            conn.execute(
                "UPDATE accounts SET reauth_required = 1, updated_at = ? WHERE id = ?",
                (now, account_id),
            )
        return True

    def clear_auth_failures(self, account_id: int) -> None:
        """Любой успешный ответ обнуляет счётчик: считаем именно подряд идущие."""
        with self.connect() as conn:
            conn.execute(
                "UPDATE accounts SET auth_failures = 0 WHERE id = ? AND auth_failures != 0",
                (account_id,),
            )

    def get_account_profile(self, account_id: int) -> dict | None:
        """То, что уезжает в приложение: без токенов и служебных полей опроса."""
        with self.connect() as conn:
            row = conn.execute(
                """
                SELECT company_id, staff_id, user_name, avatar_url
                FROM accounts WHERE id = ?
                """,
                (account_id,),
            ).fetchone()
        return dict(row) if row else None

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

    def get_account(self, account_id: int) -> WatchedAccount | None:
        with self.connect() as conn:
            row = conn.execute(
                """
                SELECT id, company_id, staff_id, user_token_enc, reauth_required,
                       changed_after, backoff_until, consecutive_errors
                FROM accounts WHERE id = ?
                """,
                (account_id,),
            ).fetchone()
        return _row_to_account(row) if row else None

    def list_accounts(self) -> list[WatchedAccount]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT id, company_id, staff_id, user_token_enc, reauth_required,
                       changed_after, backoff_until, consecutive_errors
                FROM accounts
                ORDER BY id
                """
            ).fetchall()
        return [_row_to_account(row) for row in rows]

    def list_devices_for_account(self, account_id: int) -> list[RegisteredDevice]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT id, account_id, device_id, fcm_token, label, app_version,
                       last_ack_event_id
                FROM devices
                WHERE account_id = ? AND revoked_at IS NULL
                ORDER BY id
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

    def has_record_states(self, account_id: int) -> bool:
        with self.connect() as conn:
            row = conn.execute(
                "SELECT 1 FROM record_states WHERE account_id = ? LIMIT 1",
                (account_id,),
            ).fetchone()
        return row is not None

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

    def _write_record_states(
        self,
        conn: sqlite3.Connection,
        account_id: int,
        changed: list[RecordState],
        removed: list[int],
    ) -> None:
        """Только дифф.

        Раньше здесь был `DELETE` всех состояний аккаунта плюс полный `INSERT`
        заново — и так каждые 10 секунд, независимо от того, изменилось ли хоть
        что-то. На SD-карте Pi это самая дорогая операция сервиса, притом почти
        всегда переписывающая таблицу саму в себя.
        """
        if removed:
            conn.executemany(
                "DELETE FROM record_states WHERE account_id = ? AND record_id = ?",
                [(account_id, record_id) for record_id in removed],
            )
        if not changed:
            return
        now = utc_now_iso()
        conn.executemany(
            """
            INSERT INTO record_states (
                account_id, record_id, date, time, attendance, deleted,
                client_name, kind, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(account_id, record_id) DO UPDATE SET
                date = excluded.date,
                time = excluded.time,
                attendance = excluded.attendance,
                deleted = excluded.deleted,
                client_name = excluded.client_name,
                kind = excluded.kind,
                updated_at = excluded.updated_at
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
                for state in changed
            ],
        )

    def replace_record_states(
        self, account_id: int, states: dict[int, RecordState]
    ) -> None:
        changed, removed = _record_state_changes(
            self.get_record_states(account_id), states
        )
        if not changed and not removed:
            return
        with self.connect() as conn:
            self._write_record_states(conn, account_id, changed, removed)

    def _insert_events(
        self, conn: sqlite3.Connection, account_id: int, events: list[EventLike]
    ) -> list[int]:
        now = utc_now_iso()
        ids = []
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

    def insert_events(self, account_id: int, events: list[EventLike]) -> list[int]:
        with self.connect() as conn:
            return self._insert_events(conn, account_id, events)

    def commit_poll_result(
        self,
        account_id: int,
        events: list[EventLike],
        states: dict[int, RecordState],
        previous_states: dict[int, RecordState] | None = None,
    ) -> list[int]:
        """Журнал и состояния — одной транзакцией (§2.2 разбора Этапа 5).

        Либо записано всё, либо ничего: при падении посередине SQLite откатит
        вставку событий вместе со сдвигом состояний, и следующий цикл пересчитает
        тот же дифф. Разделять эти две записи нельзя — см. П2 плана §3.

        `states` — полный желаемый снимок аккаунта; пишется из него только то,
        что отличается от `previous_states`. Поллер уже держит прежний снимок в
        руках и передаёт его сюда; без него он читается из базы.
        """
        previous = (
            previous_states if previous_states is not None
            else self.get_record_states(account_id)
        )
        changed, removed = _record_state_changes(previous, states)
        # Инкрементальный цикл без изменений — самый частый случай при опросе раз
        # в 10 секунд: не открываем транзакцию вообще.
        if not events and not changed and not removed:
            return []
        with self.connect() as conn:
            event_ids = self._insert_events(conn, account_id, events)
            self._write_record_states(conn, account_id, changed, removed)
        return event_ids

    def list_events_since(
        self,
        account_id: int,
        since_id: int,
        limit: int = 100,
        min_date: str | None = None,
    ) -> list[Event]:
        """`min_date` — горизонт §6.4 плана: не отдавать события про уже прошедшие занятия.

        Без него (по умолчанию) фильтра по дате нет — так пользуются внутренние
        вызовы поллера и тесты.
        """
        query = """
            SELECT id, account_id, type, client_name, date, time, kind,
                   prev_date, prev_time, record_id, created_at
            FROM events
            WHERE account_id = ? AND id > ?
        """
        params: list[object] = [account_id, since_id]
        if min_date is not None:
            query += " AND date >= ?"
            params.append(min_date)
        query += " ORDER BY id LIMIT ?"
        params.append(limit)
        with self.connect() as conn:
            rows = conn.execute(query, params).fetchall()
        return [_row_to_event(row) for row in rows]

    def get_max_event_id(self) -> int:
        with self.connect() as conn:
            row = conn.execute("SELECT COALESCE(MAX(id), 0) AS m FROM events").fetchone()
        return int(row["m"])

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
                "SELECT COUNT(*) AS c FROM events WHERE created_at >= ?",
                (utc_iso_days_ago(1),),
            ).fetchone()["c"]
        return {
            "accounts": int(accounts),
            "devices": int(devices),
            "events_today": int(events_today),
        }

    def poll_health_summary(self) -> dict:
        with self.connect() as conn:
            last = conn.execute(
                "SELECT started_at, duration_ms, error FROM poll_runs ORDER BY id DESC LIMIT 1"
            ).fetchone()
            errors_today = conn.execute(
                """
                SELECT COUNT(*) AS c FROM poll_runs
                WHERE error IS NOT NULL AND started_at >= ?
                """,
                (utc_iso_days_ago(1),),
            ).fetchone()["c"]
        return {
            "last_polled_at": last["started_at"] if last else None,
            "last_poll_duration_ms": int(last["duration_ms"]) if last else None,
            "last_poll_error": last["error"] if last else None,
            "errors_today": int(errors_today),
        }

    def list_recent_events_admin(self, limit: int = 50) -> list[dict]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT
                    e.id, e.type, e.client_name, e.date, e.time, e.kind,
                    e.prev_date, e.prev_time, e.created_at,
                    a.company_id, a.staff_id,
                    (SELECT COUNT(*) FROM push_deliveries pd
                     WHERE pd.event_id = e.id AND pd.status = 'sent') AS delivered,
                    (SELECT COUNT(*) FROM push_deliveries pd
                     WHERE pd.event_id = e.id) AS targets
                FROM events e
                JOIN accounts a ON a.id = e.account_id
                ORDER BY e.id DESC
                LIMIT ?
                """,
                (limit,),
            ).fetchall()
        return [dict(row) for row in rows]

    def list_accounts_admin(self) -> list[dict]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT
                    a.id, a.company_id, a.staff_id, a.changed_after,
                    a.backoff_until, a.consecutive_errors,
                    a.last_polled_at, a.last_error,
                    COUNT(d.id) AS device_count
                FROM accounts a
                LEFT JOIN devices d ON d.account_id = a.id
                GROUP BY a.id
                ORDER BY a.id
                """
            ).fetchall()
        return [dict(row) for row in rows]

    def list_devices_admin(self) -> list[dict]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT
                    d.device_id, d.label, d.app_version, d.last_seen_at,
                    d.last_ack_event_id,
                    a.id AS account_id, a.company_id, a.staff_id,
                    (SELECT COUNT(*) FROM push_deliveries pd
                     WHERE pd.device_id = d.device_id) AS delivery_count
                FROM devices d
                JOIN accounts a ON a.id = d.account_id
                ORDER BY d.id
                """
            ).fetchall()
        return [dict(row) for row in rows]

    def get_device_admin(self, device_id: str) -> dict | None:
        with self.connect() as conn:
            row = conn.execute(
                """
                SELECT
                    d.device_id, d.label, d.app_version, d.last_seen_at,
                    d.last_ack_event_id,
                    a.id AS account_id, a.company_id, a.staff_id
                FROM devices d
                JOIN accounts a ON a.id = d.account_id
                WHERE d.device_id = ?
                """,
                (device_id,),
            ).fetchone()
        return dict(row) if row else None

    def list_deliveries_for_device(self, device_id: str, limit: int = 100) -> list[dict]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                SELECT
                    pd.id AS delivery_id, pd.status, pd.detail, pd.created_at AS delivered_at,
                    e.id AS event_id, e.type, e.client_name, e.date, e.time, e.kind,
                    e.prev_date, e.prev_time
                FROM push_deliveries pd
                JOIN events e ON e.id = pd.event_id
                WHERE pd.device_id = ?
                ORDER BY pd.id DESC
                LIMIT ?
                """,
                (device_id, limit),
            ).fetchall()
        return [dict(row) for row in rows]

    def list_poll_runs_admin(
        self, limit: int = 20, offset: int = 0, only_significant: bool = False
    ) -> list[dict]:
        where = f"WHERE {SIGNIFICANT_POLL_RUN}" if only_significant else ""
        with self.connect() as conn:
            rows = conn.execute(
                f"""
                SELECT id, company_id, started_at, duration_ms, records_fetched,
                       events_created, pushes_sent, error
                FROM poll_runs
                {where}
                ORDER BY id DESC
                LIMIT ? OFFSET ?
                """,
                (limit, offset),
            ).fetchall()
        return [dict(row) for row in rows]

    def count_poll_runs(self, only_significant: bool = False) -> int:
        """Сколько циклов лежит в базе — дашборд по этому числу решает,
        показывать ли ссылку «дальше» (хранение ограничено purge_old_data)."""
        where = f"WHERE {SIGNIFICANT_POLL_RUN}" if only_significant else ""
        with self.connect() as conn:
            return int(
                conn.execute(f"SELECT COUNT(*) AS c FROM poll_runs {where}").fetchone()["c"]
            )


def _row_to_account(row: sqlite3.Row) -> WatchedAccount:
    return WatchedAccount(
        id=int(row["id"]),
        company_id=int(row["company_id"]),
        staff_id=int(row["staff_id"]),
        user_token_enc=row["user_token_enc"],
        reauth_required=bool(row["reauth_required"]),
        changed_after=row["changed_after"],
        backoff_until=row["backoff_until"],
        consecutive_errors=int(row["consecutive_errors"]),
    )


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
