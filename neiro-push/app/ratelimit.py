"""Лимиты запросов.

Счётчики в памяти процесса, а не в базе. Сервис живёт одним контейнером с одним
воркером uvicorn, так что общий словарь и есть общее состояние; перезапуск
счётчики обнуляет — для защиты от перебора это приемлемо, а SQLite на SD-карте
Pi лишней записи на каждый запрос не заслужил.

Если воркеров когда-нибудь станет больше одного, лимиты придётся вынести
наружу: каждый процесс будет считать своё, и фактический порог умножится на
число воркеров.
"""

from __future__ import annotations

import math
import threading
import time

# API.md § Лимиты.
LOGIN_LIMIT = 5
LOGIN_WINDOW_SECONDS = 15 * 60
PROXY_LIMIT = 60
PROXY_WINDOW_SECONDS = 60


class RateLimiter:
    """Скользящее окно: помним отметки времени попыток и выкидываем старые."""

    def __init__(self) -> None:
        self._hits: dict[str, list[float]] = {}
        self._lock = threading.Lock()

    def check(self, key: str, limit: int, window_seconds: int) -> int | None:
        """Засчитывает попытку. Возвращает `Retry-After` в секундах, если лимит
        исчерпан, и `None`, если можно продолжать.

        Исчерпанная попытка **не** добавляется в окно: иначе непрерывно
        долбящий клиент продлевал бы себе блокировку бесконечно.
        """
        now = time.monotonic()
        cutoff = now - window_seconds
        with self._lock:
            hits = [t for t in self._hits.get(key, ()) if t > cutoff]
            if len(hits) >= limit:
                self._hits[key] = hits
                retry_after = math.ceil(hits[0] + window_seconds - now)
                return max(retry_after, 1)
            hits.append(now)
            self._hits[key] = hits
            return None

    def reset(self, key: str) -> None:
        """Снимает накопленное — например, после удачного входа."""
        with self._lock:
            self._hits.pop(key, None)

    def purge(self, older_than_seconds: int = LOGIN_WINDOW_SECONDS) -> None:
        """Убирает ключи, по которым давно ничего не было.

        Без этого словарь растёт на каждый новый IP и живёт до перезапуска.
        """
        cutoff = time.monotonic() - older_than_seconds
        with self._lock:
            for key in [k for k, hits in self._hits.items() if not hits or hits[-1] <= cutoff]:
                self._hits.pop(key, None)


def client_ip(request) -> str:
    """IP клиента с поправкой на nginx.

    Сервис стоит за reverse-прокси на VPS, поэтому `request.client.host` — это
    всегда конец туннеля, один и тот же для всех. Реальный адрес приходит в
    `X-Forwarded-For`, первым в списке.
    """
    forwarded = request.headers.get("x-forwarded-for", "")
    if forwarded:
        return forwarded.split(",")[0].strip()
    return request.client.host if request.client else "unknown"
