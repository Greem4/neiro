#!/usr/bin/env bash
# Открывает neiro-push наружу на push.neiro.greemlab.ru. Идемпотентно.
#
# ГЛАВНОЕ ОТЛИЧИЕ ОТ /v2: proxy_pass БЕЗ завершающего слеша.
# У neiro-push-events `proxy_pass … :18082/` срезает префикс, поэтому внутри
# контейнера пути начинаются с /v1, а публично получается /v2/v1/... — два
# номера версии подряд. Здесь префикс не срезается: что написано в приложении,
# то и обрабатывает FastAPI (DEPLOY.md § Публичный маршрут).
#
# Локаций три, а не одна. В DEPLOY.md описана только /v1/, но /health и
# /dashboard в приложении лежат вне этого префикса — с одной локацией они
# остались бы недоступны снаружи, а `location /` на этом сайте всё ещё смотрит
# на туннель мёртвого первого поколения (18080). Его не трогаем.
set -euo pipefail

VPS_SSH="${NEIRO_PUSH_VPS_SSH:-roster-vps}"
NEIRO_PUSH_PUBLIC_HOST="${NEIRO_PUSH_PUBLIC_HOST:-push.neiro.greemlab.ru}"
TUNNEL_PORT="${NEIRO_PUSH_TUNNEL_PORT:-18083}"

ssh "${VPS_SSH}" env HOST="${NEIRO_PUSH_PUBLIC_HOST}" TUNNEL_PORT="${TUNNEL_PORT}" bash -s <<'REMOTE'
set -euo pipefail
SITE="/etc/nginx/sites-available/${HOST}"

if ! sudo test -f "${SITE}"; then
  echo "no_site_file:${SITE}" >&2
  exit 1
fi

sudo cp "${SITE}" "${SITE}.bak-$(date +%Y%m%d-%H%M%S)"

sudo python3 - "${SITE}" "${TUNNEL_PORT}" <<'PY'
import sys

site_path, port = sys.argv[1], sys.argv[2]
with open(site_path, encoding="utf-8") as f:
    text = f.read()

common = (
    "        proxy_http_version 1.1;\n"
    "        proxy_set_header Host $host;\n"
    "        proxy_set_header X-Real-IP $remote_addr;\n"
    "        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n"
    "        proxy_set_header X-Forwarded-Proto $scheme;\n"
    "    }\n\n"
)

# Без слеша после порта: nginx отдаёт бэкенду путь целиком, включая префикс.
wanted = [
    ("location /v1/", f"    location /v1/ {{\n        proxy_pass http://127.0.0.1:{port};\n" + common),
    ("location /dashboard", f"    location /dashboard {{\n        proxy_pass http://127.0.0.1:{port};\n" + common),
    ("location /health", f"    location /health {{\n        proxy_pass http://127.0.0.1:{port};\n" + common),
]

marker = "    location / {"
added = []
for needle, block in wanted:
    if needle in text:
        continue
    idx = text.find(marker)
    if idx == -1:
        print("no_location_root", file=sys.stderr)
        sys.exit(1)
    text = text[:idx] + block + text[idx:]
    added.append(needle)

with open(site_path, "w", encoding="utf-8") as f:
    f.write(text)
print("added:" + (",".join(added) if added else "nothing"))
PY

sudo nginx -t
sudo systemctl reload nginx
echo "reloaded"
REMOTE
