import json
from pathlib import Path

from app.yclients import YClientsClient, next_changed_after

# Запись снята с настоящего ответа `/records/`, но обезличена: клиент, мастер,
# услуга, комментарии и ссылки выдуманы, цена подменена. Форма ответа при этом
# настоящая — ради неё тесты и существуют: `date` с временем внутри,
# `datetime` со смещением через двоеточие, `last_change_date` — без него.
#
# Раньше файл читался из `tools/yclients-sandbox/exports/`, а весь `tools/`
# лежит в .gitignore — на машине автора тесты проходили, в CI все девять падали
# с FileNotFoundError. Фикстура живёт рядом с тестами и едет вместе с ними.
FIXTURE_PATH = Path(__file__).parent / "fixtures" / "yclients_record.json"


def _real_record() -> dict:
    data = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
    return data["records"][0]


def test_date_extracted_from_datetime_not_date_field() -> None:
    raw = _real_record()
    assert raw["date"] == "2026-06-28 18:00:00"

    record = YClientsClient._parse_record(raw)

    assert record.date == "2026-06-28"


def test_time_extracted_without_timezone_recalculation() -> None:
    raw = _real_record()
    assert raw["datetime"] == "2026-06-28T18:00:00+03:00"

    record = YClientsClient._parse_record(raw)

    assert record.time == "18:00"


def test_client_name_uses_display_name_when_present() -> None:
    raw = _real_record()

    record = YClientsClient._parse_record(raw)

    assert record.client_name == raw["client"]["display_name"]


def test_client_name_falls_back_to_name_and_surname() -> None:
    raw = _real_record()
    raw["client"] = {"display_name": "", "name": "Иван", "surname": "Петров"}

    record = YClientsClient._parse_record(raw)

    assert record.client_name == "Иван Петров"


def test_kind_is_diagnostics_when_service_title_matches() -> None:
    raw = _real_record()
    raw["services"] = [{"title": "ДИАГНОСТИКА развития"}]

    record = YClientsClient._parse_record(raw)

    assert record.kind == "DIAGNOSTICS"


def test_kind_is_lesson_when_no_diagnostics_service() -> None:
    raw = _real_record()

    record = YClientsClient._parse_record(raw)

    assert record.kind == "LESSON"


def test_staff_id_defaults_to_zero_when_missing() -> None:
    raw = _real_record()
    del raw["staff_id"]

    record = YClientsClient._parse_record(raw)

    assert record.staff_id == 0


def test_next_changed_after_parses_offset_without_colon() -> None:
    raw = _real_record()
    assert raw["last_change_date"] == "2026-01-22T17:49:21+0300"

    record = YClientsClient._parse_record(raw)
    cursor = next_changed_after([record])

    assert cursor == "2026-01-22 17:49:16"


def test_next_changed_after_none_without_last_change_date() -> None:
    raw = _real_record()
    raw["last_change_date"] = None

    record = YClientsClient._parse_record(raw)

    assert next_changed_after([record]) is None
