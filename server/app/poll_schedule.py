from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from zoneinfo import ZoneInfo


@dataclass(frozen=True)
class PollSchedule:
    """Интервалы опроса YClients по МСК (как в Android LiveApiPollSchedule)."""

    zone: ZoneInfo = ZoneInfo("Europe/Moscow")
    day_interval_seconds: int = 15
    night_interval_seconds: int = 3600
    day_start_hour: int = 9
    quiet_start_hour: int = 21

    def interval_seconds(self, now: datetime | None = None) -> int:
        current = now or datetime.now(self.zone)
        if self.is_quiet_hours(current):
            return self.night_interval_seconds
        return self.day_interval_seconds

    def is_quiet_hours(self, now: datetime | None = None) -> bool:
        current = now or datetime.now(self.zone)
        hour = current.hour
        return hour >= self.quiet_start_hour or hour < self.day_start_hour


def get_poll_schedule(
    day_interval_seconds: int,
    night_interval_seconds: int,
) -> PollSchedule:
    return PollSchedule(
        day_interval_seconds=day_interval_seconds,
        night_interval_seconds=night_interval_seconds,
    )
