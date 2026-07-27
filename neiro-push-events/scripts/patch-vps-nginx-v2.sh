#!/usr/bin/env bash
# Добавляет location /v2/ (проксирует на туннельный порт neiro-push-events)
# в существующий nginx-сайт push.neiro.greemlab.ru на VPS. Идемпотентно.
set -euo pipefail

VPS_SSH="${NEIRO_PUSH_EVENTS_VPS_SSH:-roster-vps}"
NEIRO_PUSH_PUBLIC_HOST="${NEIRO_PUSH_PUBLIC_HOST:-push.neiro.greemlab.ru}"
TUNNEL_PORT="${NEIRO_PUSH_EVENTS_TUNNEL_PORT:-18082}"

ssh "${VPS_SSH}" env HOST="${NEIRO_PUSH_PUBLIC_HOST}" TUNNEL_PORT="${TUNNEL_PORT}" bash -s <<'REMOTE'
set -euo pipefail
SITE="/etc/nginx/sites-available/${HOST}"

if ! sudo test -f "${SITE}"; then
  echo "no_site_file:${SITE}" >&2
  exit 1
fi

if sudo grep -qF "location /v2/" "${SITE}"; then
  echo "already_patched"
  exit 0
fi

sudo python3 - "${SITE}" "${TUNNEL_PORT}" <<'PY'
import sys

site_path, port = sys.argv[1], sys.argv[2]
with open(site_path, encoding="utf-8") as f:
    text = f.read()

block = (
    "    location /v2/ {\n"
    f"        proxy_pass http://127.0.0.1:{port}/;\n"
    "        proxy_http_version 1.1;\n"
    "        proxy_set_header Host $host;\n"
    "        proxy_set_header X-Real-IP $remote_addr;\n"
    "        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n"
    "        proxy_set_header X-Forwarded-Proto $scheme;\n"
    "    }\n\n"
)

marker = "    location / {"
idx = text.find(marker)
if idx == -1:
    print("no_location_root", file=sys.stderr)
    sys.exit(1)

text = text[:idx] + block + text[idx:]
with open(site_path, "w", encoding="utf-8") as f:
    f.write(text)
print("patched")
PY

sudo nginx -t
sudo systemctl reload nginx
echo "reloaded"
REMOTE
