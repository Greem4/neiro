import unittest
from datetime import datetime
from zoneinfo import ZoneInfo

from app.poll_schedule import PollSchedule


class PollScheduleTest(unittest.TestCase):
    def test_day_interval(self) -> None:
        schedule = PollSchedule(day_interval_seconds=15, night_interval_seconds=3600)
        noon = datetime(2026, 6, 25, 12, 0, tzinfo=ZoneInfo("Europe/Moscow"))
        self.assertEqual(schedule.interval_seconds(noon), 15)

    def test_night_interval(self) -> None:
        schedule = PollSchedule(day_interval_seconds=15, night_interval_seconds=3600)
        night = datetime(2026, 6, 25, 22, 0, tzinfo=ZoneInfo("Europe/Moscow"))
        self.assertEqual(schedule.interval_seconds(night), 3600)

    def test_early_morning_is_quiet(self) -> None:
        schedule = PollSchedule(day_interval_seconds=15, night_interval_seconds=3600)
        early = datetime(2026, 6, 25, 7, 30, tzinfo=ZoneInfo("Europe/Moscow"))
        self.assertTrue(schedule.is_quiet_hours(early))


if __name__ == "__main__":
    unittest.main()
