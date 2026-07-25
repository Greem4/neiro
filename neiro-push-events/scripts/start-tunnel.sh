#!/usr/bin/env bash
# Reverse SSH-туннель Pi → VPS для neiro-push-events (публичный маршрут /v2).
# Запускается как systemd --user юнит neiro-push-events-tunnel.service,
# см. install-tunnel.sh.
set -euo pipefail

ENV_FILE="${HOME}/.config/neiro-push-events/tunnel.env"
if [[ -f "${ENV_FILE}" ]]; then
  # shellcheck source=/dev/null
  source "${ENV_FILE}"
fi

REMOTE_USER="${REMOTE_USER:-tunnel}"
REMOTE_HOST="${REMOTE_HOST:?REMOTE_HOST не задан в ${ENV_FILE}}"
REMOTE_PORT="${REMOTE_PORT:-22}"
REMOTE_BIND_ADDR="${REMOTE_BIND_ADDR:-127.0.0.1}"
REMOTE_BIND_PORT="${REMOTE_BIND_PORT:?REMOTE_BIND_PORT не задан в ${ENV_FILE}}"
LOCAL_TARGET_ADDR="${LOCAL_TARGET_ADDR:-127.0.0.1}"
LOCAL_TARGET_PORT="${LOCAL_TARGET_PORT:?LOCAL_TARGET_PORT не задан в ${ENV_FILE}}"
SSH_KEY="${SSH_KEY:?SSH_KEY не задан в ${ENV_FILE}}"

while true; do
  /usr/bin/ssh -N \
    -o ServerAliveInterval=30 \
    -o ServerAliveCountMax=3 \
    -o ExitOnForwardFailure=yes \
    -o StrictHostKeyChecking=accept-new \
    -i "${SSH_KEY}" -p "${REMOTE_PORT}" \
    -R "${REMOTE_BIND_ADDR}:${REMOTE_BIND_PORT}:${LOCAL_TARGET_ADDR}:${LOCAL_TARGET_PORT}" \
    "${REMOTE_USER}@${REMOTE_HOST}" || true
  sleep 3
done
