#!/usr/bin/env python3
"""Вставляет маршрут /v2/* для neiro-push-events внутрь существующего vhost push.neiro.greemlab.ru в Caddyfile на Pi."""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

CADDYFILE = Path("/home/greem4/server/caddy/Caddyfile")
DEFAULT_HOST = "push.neiro.greemlab.ru"
MARKER = "handle_path /v2/*"


def host() -> str:
    return os.environ.get("NEIRO_PUSH_PUBLIC_HOST", DEFAULT_HOST).strip() or DEFAULT_HOST


def main() -> int:
    domain = host()
    if not CADDYFILE.is_file():
        print(f"Нет {CADDYFILE}", file=sys.stderr)
        return 1

    text = CADDYFILE.read_text(encoding="utf-8")

    if MARKER in text:
        print(f"already_patched:{domain}")
        return 0

    block_start = re.compile(rf"^http://{re.escape(domain)} \{{\n", re.MULTILINE)
    match = block_start.search(text)
    if not match:
        print(f"no_site_block:{domain}", file=sys.stderr)
        return 1

    insertion = f"\thandle_path /v2/* {{\n\t\treverse_proxy neiro-push-events:8011\n\t}}\n"
    text = text[: match.end()] + insertion + text[match.end() :]
    CADDYFILE.write_text(text, encoding="utf-8")
    print(f"added_v2_route:{domain}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
