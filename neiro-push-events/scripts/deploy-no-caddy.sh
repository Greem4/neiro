#!/usr/bin/env bash
# Временная копия deploy.sh БЕЗ шага патча/рестарта общего Caddy на Pi —
# см. docs/push-events/progress.md «подводный камень 2»: /v2 реально идёт
# через VPS nginx + SSH-туннель, Caddy в этом пути не участвует.
# Удалить после первого нормального деплоя через штатный deploy.sh
# (после того как оттуда тоже уберут/поправят Caddy-шаг, если понадобится).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NEIRO_PUSH_PUBLIC_HOST="${NEIRO_PUSH_PUBLIC_HOST:-push.neiro.greemlab.ru}"
NEIRO_PUSH_EVENTS_PUBLIC_URL="https://${NEIRO_PUSH_PUBLIC_HOST}/v2"
# shellcheck source=./_ssh.sh
source "${ROOT_DIR}/scripts/_ssh.sh"

echo "Deploy target: ${SSH_HOST}:~/neiro-push-events"

rsync -az --delete \
  --exclude '.env' \
  --exclude 'secrets/' \
  --exclude '__pycache__/' \
  --exclude '.DS_Store' \
  --exclude 'backups/' \
  --exclude 'tests/' \
  "${ROOT_DIR}/" "${SSH_HOST}:~/neiro-push-events/"

ssh_pi bash -s <<'REMOTE'
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
  echo "Created .env"
fi

# shellcheck disable=SC1091
source .env

if [[ ! -f secrets/fcm-service-account.json && -f ~/neiro-push/secrets/fcm-service-account.json ]]; then
  cp ~/neiro-push/secrets/fcm-service-account.json secrets/fcm-service-account.json
  echo "Copied FCM credentials from neiro-push"
fi

docker network inspect server_web >/dev/null 2>&1 || docker network create server_web
docker compose up -d --build

sleep 5
curl -fsS -H "Authorization: Bearer ${ADMIN_API_KEY}" http://127.0.0.1:8011/health
echo
REMOTE

echo ""
echo "Public: ${NEIRO_PUSH_EVENTS_PUBLIC_URL}/health"
ADMIN_API_KEY="$(ssh_pi "grep ^ADMIN_API_KEY= ~/neiro-push-events/.env | cut -d= -f2-")"
curl -fsS -H "Authorization: Bearer ${ADMIN_API_KEY}" "${NEIRO_PUSH_EVENTS_PUBLIC_URL}/health" \
  && echo || echo "(что-то не так на публичном пути)"
echo ""
echo "API_KEY: ssh ${SSH_HOST} \"grep ^API_KEY= ~/neiro-push-events/.env\""
echo "ADMIN_API_KEY: ssh ${SSH_HOST} \"grep ^ADMIN_API_KEY= ~/neiro-push-events/.env\""
