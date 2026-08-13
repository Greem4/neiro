#!/usr/bin/env python3
"""Подъём версии и перенос «Не выпущено» в раздел версии CHANGELOG.

Один источник версии — version.properties в корне (docs/updater/RELEASE.md).
Скрипт правит его и CHANGELOG.md, но ничего не коммитит и не тегирует: это
делает вызывающий (workflow release-on-merge.yml или человек руками).

    python3 scripts/bump-version.py patch        # 0.1.5 → 0.1.6
    python3 scripts/bump-version.py minor        # 0.1.5 → 0.2.0
    python3 scripts/bump-version.py major        # 0.1.5 → 1.0.0
    python3 scripts/bump-version.py keep         # версию не трогать, только CHANGELOG
    python3 scripts/bump-version.py patch --dry-run

Печатает новую версию в stdout последней строкой — её и читает workflow.
"""

from __future__ import annotations

import argparse
import datetime
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VERSION_FILE = ROOT / "version.properties"
CHANGELOG = ROOT / "CHANGELOG.md"

VERSION_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
UNRELEASED_HEADING = "## [Не выпущено]"

# Тот же текст, что лежит в CHANGELOG сейчас: после переноса раздел
# возвращается в исходное состояние, а не остаётся пустой дырой.
UNRELEASED_PLACEHOLDER = (
    "Пока пусто. Сюда дописывается изменение, заметное пользователю, — тем же PR,\n"
    "которым оно вносится, а не по памяти в день выпуска. Заголовки внутри:\n"
    "«Добавлено», «Изменено», «Исправлено», «Убрано».\n"
)


def read_version() -> tuple[int, int, int]:
    text = VERSION_FILE.read_text(encoding="utf-8")
    match = re.search(r"^VERSION=(.*)$", text, re.MULTILINE)
    if not match:
        sys.exit("В version.properties нет строки VERSION=")
    raw = match.group(1).strip()
    parsed = VERSION_RE.match(raw)
    if not parsed:
        sys.exit(f"VERSION должна быть вида X.Y.Z, сейчас «{raw}»")
    return tuple(int(part) for part in parsed.groups())  # type: ignore[return-value]


def bump(current: tuple[int, int, int], kind: str) -> tuple[int, int, int]:
    major, minor, patch = current
    if kind == "keep":
        return current
    if kind == "major":
        major, minor, patch = major + 1, 0, 0
    elif kind == "minor":
        major, minor, patch = major, minor + 1, 0
    elif kind == "patch":
        patch += 1
    else:
        sys.exit(f"Неизвестный разряд «{kind}»")

    # versionCode = major*10000 + minor*100 + patch, поэтому больше 99 нельзя:
    # 0.1.100 и 0.2.0 дали бы один и тот же код, и обновление бы не встало.
    if patch > 99:
        sys.exit("patch дошёл до 99 — поднимайте minor, а не patch")
    if minor > 99:
        sys.exit("minor дошёл до 99 — поднимайте major, а не minor")
    return major, minor, patch


def write_version(version: str) -> None:
    text = VERSION_FILE.read_text(encoding="utf-8")
    updated = re.sub(r"^VERSION=.*$", f"VERSION={version}", text, count=1, flags=re.MULTILINE)
    VERSION_FILE.write_text(updated, encoding="utf-8")


def split_unreleased(text: str) -> tuple[str, str, str] | None:
    """→ (текст до раздела, тело раздела, текст после). None — раздела нет."""
    start = text.find(UNRELEASED_HEADING)
    if start == -1:
        return None
    body_start = start + len(UNRELEASED_HEADING)
    next_heading = text.find("\n## ", body_start)
    body_end = len(text) if next_heading == -1 else next_heading + 1
    return text[:start], text[body_start:body_end], text[body_end:]


def has_real_entries(body: str) -> bool:
    """Заглушка «Пока пусто…» — не изменения. Считаем только списки и заголовки."""
    for line in body.splitlines():
        stripped = line.strip()
        if stripped.startswith("###") or stripped.startswith("- ") or stripped.startswith("* "):
            return True
    return False


def move_unreleased(version: str, today: str) -> str:
    """→ что произошло с CHANGELOG, для лога workflow."""
    if not CHANGELOG.exists():
        return "CHANGELOG.md нет — пропускаю"

    text = CHANGELOG.read_text(encoding="utf-8")
    parts = split_unreleased(text)
    if parts is None:
        return f"раздела «{UNRELEASED_HEADING}» нет — пропускаю"

    before, body, after = parts
    if not has_real_entries(body):
        return "«Не выпущено» пуст — заметки соберутся из git-лога"

    if f"## [{version}]" in after or f"## [{version}]" in before:
        return f"раздел [{version}] уже есть — не трогаю"

    fresh = f"{UNRELEASED_HEADING}\n\n{UNRELEASED_PLACEHOLDER}\n"
    released = f"## [{version}] — {today}\n\n{body.strip()}\n\n"
    CHANGELOG.write_text(before + fresh + released + after.lstrip("\n"), encoding="utf-8")
    return f"перенёс «Не выпущено» в [{version}] — {today}"


def main() -> None:
    parser = argparse.ArgumentParser(description="Поднять версию и закрыть раздел CHANGELOG")
    parser.add_argument("kind", choices=["major", "minor", "patch", "keep"])
    parser.add_argument("--dry-run", action="store_true", help="ничего не писать, только показать")
    parser.add_argument("--date", default=datetime.date.today().isoformat())
    args = parser.parse_args()

    current = read_version()
    new = bump(current, args.kind)
    version = ".".join(str(part) for part in new)
    old = ".".join(str(part) for part in current)

    if args.dry_run:
        print(f"версия: {old} → {version} ({args.kind})", file=sys.stderr)
        print(version)
        return

    if new != current:
        write_version(version)
    print(f"версия: {old} → {version} ({args.kind})", file=sys.stderr)
    print(move_unreleased(version, args.date), file=sys.stderr)
    print(version)


if __name__ == "__main__":
    main()
