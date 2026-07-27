#!/usr/bin/env bash
# Деплой neiro-push-events на Raspberry Pi. Одна команда, из любой сети:
#
#   ./neiro-push-events/scripts/deploy.sh
#
# Куда попадёт — выбирает scripts/_ssh.sh: из дома напрямую (roster-b3), извне
# через VPS (roster-pi-remote). Переопределить: PI_SSH=<хост> ./scripts/deploy.sh
#
# ПУБЛИЧНЫЙ МАРШРУТ (проверен 27.07.2026, подробности в docs/deploy.md):
#
#   https://push.neiro.greemlab.ru/v2/*
#     -> DNS 176.12.65.86 = VPS
#     -> nginx на VPS, TLS от Certbot
#     -> location /v2/ -> proxy_pass 127.0.0.1:18082/   (слеш срезает /v2)
#     -> reverse SSH-туннель neiro-push-events-tunnel.service (юнит на Pi)
#     -> Pi 127.0.0.1:8011 -> контейнер
#
# Caddy на Pi в этом маршруте НЕ участвует, и трогать его отсюда не нужно.
# У него есть свой блок /v2 (через cloudflared), но DNS туда не смотрит —
# это второй, спящий путь. Раньше deploy.sh патчил Caddy впустую.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NEIRO_PUSH_PUBLIC_HOST="${NEIRO_PUSH_PUBLIC_HOST:-push.neiro.greemlab.ru}"
NEIRO_PUSH_EVENTS_PUBLIC_URL="https://${NEIRO_PUSH_PUBLIC_HOST}/v2"
TUNNEL_UNIT="neiro-push-events-tunnel.service"
# shellcheck source=./_ssh.sh
source "${ROOT_DIR}/scripts/_ssh.sh"

echo "Цель: ${SSH_HOST}:~/neiro-push-events"

rsync -az --delete \
  --exclude '.env' \
  --exclude 'secrets/' \
  --exclude '__pycache__/' \
  --exclude '.DS_Store' \
  --exclude 'backups/' \
  --exclude 'data/' \
  --exclude 'tests/' \
  "${ROOT_DIR}/" "${SSH_HOST}:~/neiro-push-events/"

ssh_pi env TUNNEL_UNIT="${TUNNEL_UNIT}" bash -s <<'REMOTE'
set -euo pipefail
cd ~/neiro-push-events
mkdir -p secrets data

if [[ ! -f .env ]]; then
  API_KEY="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
  ADMIN_API_KEY="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
  ENC_KEY="$(python3 -c 'from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())')"
  cat > .env <<EOF
API_KEY=${API_KEY}
ADMIN_API_KEY=${ADMIN_API_KEY}
TOKEN_ENCRYPTION_KEY=${ENC_KEY}
POLL_INTERVAL_SECONDS=10
POLL_NIGHT_INTERVAL_SECONDS=3600
QUIET_START_HOUR=23
FCM_CREDENTIALS_PATH=/secrets/fcm-service-account.json
FCM_PROJECT_ID=
DATABASE_PATH=/data/neiro_push_events.db
LOG_LEVEL=info
EOF
  chmod 600 .env
  echo "  создан .env"
fi

# shellcheck disable=SC1091
source .env

if [[ ! -f secrets/fcm-service-account.json && -f ~/neiro-push/secrets/fcm-service-account.json ]]; then
  cp ~/neiro-push/secrets/fcm-service-account.json secrets/fcm-service-account.json
  echo "  ключ FCM скопирован из neiro-push"
fi

if [[ ! -s secrets/fcm-service-account.json ]]; then
  echo "  ВНИМАНИЕ: нет secrets/fcm-service-account.json — пуши уходить не будут" >&2
fi

docker network inspect server_web >/dev/null 2>&1 || docker network create server_web
docker compose up -d --build

# Туннель — единственное звено между VPS и этим Pi. Контейнер может подняться,
# а публичный адрес всё равно молчать, если юнит лёг.
if ! systemctl --user is-active --quiet "${TUNNEL_UNIT}"; then
  echo "  туннель ${TUNNEL_UNIT} не активен, поднимаю"
  systemctl --user restart "${TUNNEL_UNIT}" || {
    echo "  НЕ УДАЛОСЬ поднять туннель — публичный /v2 работать не будет" >&2
    echo "  поставить заново: scripts/install-tunnel.sh" >&2
  }
fi

for _ in $(seq 1 10); do
  if curl -fsS -o /dev/null -H "Authorization: Bearer ${ADMIN_API_KEY}" \
       http://127.0.0.1:8011/health; then
    echo "  контейнер отвечает на 127.0.0.1:8011"
    break
  fi
  sleep 2
done
REMOTE

echo ""
echo "Проверяю публичный адрес..."
ADMIN_API_KEY="$(ssh_pi "grep ^ADMIN_API_KEY= ~/neiro-push-events/.env | cut -d= -f2-")"
if curl -fsS -o /dev/null --max-time 20 \
     -H "Authorization: Bearer ${ADMIN_API_KEY}" "${NEIRO_PUSH_EVENTS_PUBLIC_URL}/health"; then
  echo "  ОК: ${NEIRO_PUSH_EVENTS_PUBLIC_URL}/health"
else
  echo "  НЕ ОТВЕЧАЕТ: ${NEIRO_PUSH_EVENTS_PUBLIC_URL}/health" >&2
  echo "  Порядок разбора — docs/deploy.md, раздел «Если публичный адрес молчит»." >&2
  exit 1
fi

cat <<INFO

Готово.
  Дашборд:  ${NEIRO_PUSH_EVENTS_PUBLIC_URL}/dashboard
  Логи:     ./scripts/logs.sh
  Ключи:    ssh ${SSH_HOST} "grep -E '^(API_KEY|ADMIN_API_KEY)=' ~/neiro-push-events/.env"

  В приложение (local.properties):
    NEIRO_PUSH_API_BASE_URL=${NEIRO_PUSH_EVENTS_PUBLIC_URL}
INFO
