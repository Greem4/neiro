#!/usr/bin/env python3
"""Добавляет отдельный vhost для neiro-push в Caddyfile на Pi (~/server/caddy/Caddyfile)."""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

CADDYFILE = Path("/home/greem4/server/caddy/Caddyfile")
DEFAULT_HOST = "push.neiro.greemlab.ru"
OLD_PATH_MARKER = "handle /neiro-push/*"


def host() -> str:
    return os.environ.get("NEIRO_PUSH_PUBLIC_HOST", DEFAULT_HOST).strip() or DEFAULT_HOST


def site_marker(domain: str) -> str:
    return f"http://{domain} {{"


def site_block(domain: str) -> str:
    return f"""http://{domain} {{
\treverse_proxy neiro-push:8010
}}
"""


def remove_legacy_path_block(text: str) -> str:
    pattern = re.compile(
        r"\n?\thandle /neiro-push/\* \{[^}]*\}\n?",
        re.MULTILINE,
    )
    return pattern.sub("\n", text)


def remove_legacy_site_block(text: str, domain: str) -> str:
    for prefix in (f"http://{domain}", domain):
        pattern = re.compile(
            rf"\n?{re.escape(prefix)} \{{[^}}]*\}}\n?",
            re.MULTILINE | re.DOTALL,
        )
        text = pattern.sub("\n", text)
    return text


def main() -> int:
    domain = host()
    if not CADDYFILE.is_file():
        print(f"Нет {CADDYFILE}", file=sys.stderr)
        return 1

    text = CADDYFILE.read_text(encoding="utf-8")
    changed = False

    if OLD_PATH_MARKER in text:
        text = remove_legacy_path_block(text)
        changed = True
        print("removed_legacy_path_route")

    marker = site_marker(domain)
    if marker not in text:
        text = remove_legacy_site_block(text, domain)
        text = text.rstrip() + "\n\n" + site_block(domain) + "\n"
        changed = True
        print(f"added_site:{domain}")
    else:
        print(f"already_patched:{domain}")

    if changed:
        CADDYFILE.write_text(text, encoding="utf-8")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
