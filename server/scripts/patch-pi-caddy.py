#!/usr/bin/env python3
"""Добавляет маршрут /neiro-push в Caddyfile на Pi (~/server/caddy/Caddyfile)."""
from __future__ import annotations

from pathlib import Path
import sys

CADDYFILE = Path("/home/greem4/server/caddy/Caddyfile")
MARKER = "handle /neiro-push/*"
BLOCK = """\thandle /neiro-push/* {
\t\turi strip_prefix /neiro-push
\t\treverse_proxy neiro-push:8010
\t}

"""


def main() -> int:
    if not CADDYFILE.is_file():
        print(f"Нет {CADDYFILE}", file=sys.stderr)
        return 1

    text = CADDYFILE.read_text(encoding="utf-8")
    if MARKER in text:
        print("already_patched")
        return 0

    needle = "\thandle /api/* {"
    if needle not in text:
        print("pattern /api/* not found in Caddyfile", file=sys.stderr)
        return 1

    # Вставляем сразу после блока /api/* (после закрывающей скобки handle).
    api_start = text.index(needle)
    api_end = text.index("\n\t}", api_start) + len("\n\t}")
    updated = text[:api_end] + "\n\n" + BLOCK.rstrip("\n") + text[api_end:]
    CADDYFILE.write_text(updated, encoding="utf-8")
    print("patched_ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
