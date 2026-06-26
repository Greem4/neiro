#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REMOTE_DIR="${NEIRO_PUSH_DIR:-~/neiro-push}"
NEIRO_PUSH_PUBLIC_HOST="${NEIRO_PUSH_PUBLIC_HOST:-push.neiro.greemlab.ru}"
NEIRO_PUSH_PUBLIC_URL="https://${NEIRO_PUSH_PUBLIC_HOST}"
# shellcheck source=./_ssh.sh
source "${ROOT_DIR}/scripts/_ssh.sh"

echo "Deploy target: ${SSH_HOST}:${REMOTE_DIR}"

rsync -az --delete \
  --exclude '.env' \
  --exclude 'secrets/' \
  --exclude '__pycache__/' \
  --exclude '.DS_Store' \
  "${ROOT_DIR}/" "${SSH_HOST}:${REMOTE_DIR}/"

ssh_pi env NEIRO_PUSH_PUBLIC_HOST="${NEIRO_PUSH_PUBLIC_HOST}" bash -s <<'REMOTE'
set -euo pipefail
cd ~/neiro-push
mkdir -p secrets data

if [[ ! -f .env ]]; then
  API_KEY="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
  ENC_KEY="$(python3 -c 'from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())')"
  cat > .env <<EOF
API_KEY=${API_KEY}
TOKEN_ENCRYPTION_KEY=${ENC_KEY}
POLL_INTERVAL_SECONDS=15
POLL_NIGHT_INTERVAL_SECONDS=3600
FCM_CREDENTIALS_PATH=/secrets/fcm-service-account.json
FCM_PROJECT_ID=
DATABASE_PATH=/data/neiro_push.db
LOG_LEVEL=info
EOF
  chmod 600 .env
  echo "Created .env"
else
  grep -q '^POLL_INTERVAL_SECONDS=' .env || echo 'POLL_INTERVAL_SECONDS=15' >> .env
  grep -q '^POLL_NIGHT_INTERVAL_SECONDS=' .env || echo 'POLL_NIGHT_INTERVAL_SECONDS=3600' >> .env
  sed -i 's/^POLL_INTERVAL_SECONDS=.*/POLL_INTERVAL_SECONDS=15/' .env
  sed -i 's/^POLL_NIGHT_INTERVAL_SECONDS=.*/POLL_NIGHT_INTERVAL_SECONDS=3600/' .env
fi

docker network inspect server_web >/dev/null 2>&1 || docker network create server_web
docker compose up -d --build

if [[ -f ~/server/caddy/Caddyfile ]]; then
  python3 ~/neiro-push/scripts/patch-pi-caddy.py
  cd ~/server
  docker compose up -d
  docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile 2>/dev/null \
    || docker compose restart caddy
fi

sleep 5
curl -fsS http://127.0.0.1:8010/health
echo
curl -fsS -H "Host: ${NEIRO_PUSH_PUBLIC_HOST}" http://127.0.0.1/health
echo
REMOTE

echo ""
echo "Public: ${NEIRO_PUSH_PUBLIC_URL}/health"
curl -fsS "${NEIRO_PUSH_PUBLIC_URL}/health" && echo || echo "(VPS/nginx not ready yet)"
echo ""
echo "API_KEY: ssh ${SSH_HOST} \"grep ^API_KEY= ~/neiro-push/.env\""
echo "local.properties:"
echo "  NEIRO_PUSH_API_BASE_URL=${NEIRO_PUSH_PUBLIC_URL}"
echo "  NEIRO_PUSH_API_KEY=<API_KEY с Pi>"
