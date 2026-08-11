#!/usr/bin/env bash
# Деплой neiro-push на Raspberry Pi. Одна команда, из любой сети:
#
#   ./neiro-push/scripts/deploy.sh
#
# Куда попадёт — выбирает scripts/_ssh.sh: из дома напрямую (roster-b3), извне
# через VPS (roster-pi-remote). Переопределить: PI_SSH=<хост> ./scripts/deploy.sh
#
# ПУБЛИЧНЫЙ МАРШРУТ (DEPLOY.md § Публичный маршрут):
#
#   https://push.neiro.greemlab.ru/v1/*
#     -> DNS 176.12.65.86 = VPS
#     -> nginx на VPS, TLS от Certbot
#     -> location /v1/ -> proxy_pass 127.0.0.1:18083   (БЕЗ слеша: префикс не срезается)
#     -> reverse SSH-туннель neiro-push-tunnel.service (юнит на Pi)
#     -> Pi 127.0.0.1:8012 -> контейнер
#
# neiro-push-events (8011, /v2) работает рядом и этим скриптом не трогается.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NEIRO_PUSH_PUBLIC_HOST="${NEIRO_PUSH_PUBLIC_HOST:-push.neiro.greemlab.ru}"
NEIRO_PUSH_PUBLIC_URL="https://${NEIRO_PUSH_PUBLIC_HOST}"
TUNNEL_UNIT="neiro-push-tunnel.service"
# shellcheck source=./_ssh.sh
source "${ROOT_DIR}/scripts/_ssh.sh"

echo "Цель: ${SSH_HOST}:~/neiro-push"

rsync -az --delete \
  --exclude '.env' \
  --exclude 'secrets/' \
  --exclude '__pycache__/' \
  --exclude '.DS_Store' \
  --exclude 'backups/' \
  --exclude 'data/' \
  --exclude 'tests/' \
  "${ROOT_DIR}/" "${SSH_HOST}:~/neiro-push/"

ssh_pi env TUNNEL_UNIT="${TUNNEL_UNIT}" bash -s <<'REMOTE'
set -euo pipefail
cd ~/neiro-push
mkdir -p secrets data

if [[ ! -f .env ]]; then
  API_KEY="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
  ADMIN_API_KEY="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
  ENC_KEY="$(python3 -c 'from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())')"
  cat > .env <<ENVEOF
API_KEY=${API_KEY}
ADMIN_API_KEY=${ADMIN_API_KEY}
TOKEN_ENCRYPTION_KEY=${ENC_KEY}
YCLIENTS_PARTNER_TOKEN=
YCLIENTS_COMPANY_ID=
POLL_INTERVAL_SECONDS=10
POLL_NIGHT_INTERVAL_SECONDS=3600
QUIET_START_HOUR=23
FCM_CREDENTIALS_PATH=/secrets/fcm-service-account.json
FCM_PROJECT_ID=
DATABASE_PATH=/data/neiro_push.db
LOG_LEVEL=info
ENVEOF
  chmod 600 .env
  echo "  создан .env"
fi

# shellcheck disable=SC1091
source .env

# Сервис без партнёрского токена не сможет ни залогинить пользователя, ни
# опросить YClients: он один на весь сервис и генерации не подлежит —
# значение берётся из local.properties на Mac (DEPLOY.md § Переменные окружения).
if [[ -z "${YCLIENTS_PARTNER_TOKEN:-}" ]]; then
  echo "  ОШИБКА: YCLIENTS_PARTNER_TOKEN пуст в ~/neiro-push/.env" >&2
  echo "  Без него вход и опрос YClients работать не будут. Положите значение руками:" >&2
  echo "    ssh <pi> \"nano ~/neiro-push/.env\"" >&2
  exit 1
fi
if [[ -z "${YCLIENTS_COMPANY_ID:-}" || "${YCLIENTS_COMPANY_ID}" == "0" ]]; then
  echo "  ОШИБКА: YCLIENTS_COMPANY_ID пуст в ~/neiro-push/.env" >&2
  exit 1
fi

if [[ ! -f secrets/fcm-service-account.json && -f ~/neiro-push-events/secrets/fcm-service-account.json ]]; then
  cp ~/neiro-push-events/secrets/fcm-service-account.json secrets/fcm-service-account.json
  echo "  ключ FCM скопирован из neiro-push-events"
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
    echo "  НЕ УДАЛОСЬ поднять туннель — публичный /v1 работать не будет" >&2
    echo "  поставить заново: scripts/install-tunnel.sh" >&2
  }
fi

# Пересобранный контейнер на Pi поднимается небыстро: Docker уже слушает порт,
# а uvicorn внутри ещё стартует, и curl получает "reset by peer". Ждём до 90 с
# и молчим про промежуточные неудачи — иначе экран заливает мусором.
echo -n "  жду приложение"
ok=""
for _ in $(seq 1 30); do
  if curl -fsS -o /dev/null --max-time 5 -H "Authorization: Bearer ${ADMIN_API_KEY}" \
       http://127.0.0.1:8012/health 2>/dev/null; then
    ok="1"
    break
  fi
  echo -n "."
  sleep 3
done
echo
if [[ -n "${ok}" ]]; then
  echo "  контейнер отвечает на 127.0.0.1:8012"
else
  echo "  контейнер не ответил за 90 с — смотри: docker compose logs --tail=50" >&2
  exit 1
fi
REMOTE

echo ""
echo -n "Проверяю публичный адрес"
ADMIN_API_KEY="$(ssh_pi "grep ^ADMIN_API_KEY= ~/neiro-push/.env | cut -d= -f2-")"
# Тоже с повторами: nginx на VPS может успеть отдать 502, пока туннель
# переподключается к только что пересозданному контейнеру.
public_ok=""
for _ in $(seq 1 10); do
  if curl -fsS -o /dev/null --max-time 10 \
       -H "Authorization: Bearer ${ADMIN_API_KEY}" \
       "${NEIRO_PUSH_PUBLIC_URL}/health" 2>/dev/null; then
    public_ok="1"
    break
  fi
  echo -n "."
  sleep 3
done
echo
if [[ -z "${public_ok}" ]]; then
  echo "  НЕ ОТВЕЧАЕТ: ${NEIRO_PUSH_PUBLIC_URL}/health" >&2
  echo "  Проверь туннель (scripts/install-tunnel.sh) и nginx (scripts/patch-vps-nginx-v1.sh)." >&2
  exit 1
fi
echo "  ОК: ${NEIRO_PUSH_PUBLIC_URL}/health"

cat <<INFO

Готово.
  Дашборд:  ${NEIRO_PUSH_PUBLIC_URL}/dashboard
  Логи:     ./scripts/logs.sh
  Ключи:    ssh ${SSH_HOST} "grep -E '^(API_KEY|ADMIN_API_KEY)=' ~/neiro-push/.env"

  В приложение (local.properties):
    NEIRO_PUSH_API_BASE_URL=${NEIRO_PUSH_PUBLIC_URL}
INFO
