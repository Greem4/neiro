#!/usr/bin/env bash
# Текстовый дашборд neiro-push-events прямо в терминал (§8.5 плана).
#
#   ./neiro-push-events/scripts/dash.sh            # разовый снимок
#   ./neiro-push-events/scripts/dash.sh --watch    # обновление раз в 10 с
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=./_ssh.sh
source "${ROOT_DIR}/scripts/_ssh.sh"

NEIRO_PUSH_PUBLIC_HOST="${NEIRO_PUSH_PUBLIC_HOST:-push.neiro.greemlab.ru}"
BASE_URL="https://${NEIRO_PUSH_PUBLIC_HOST}/v2"

if [[ -n "${NEIRO_PUSH_EVENTS_ADMIN_KEY:-}" ]]; then
  ADMIN_KEY="${NEIRO_PUSH_EVENTS_ADMIN_KEY}"
else
  ADMIN_KEY="$(ssh_pi "grep ^ADMIN_API_KEY= ~/neiro-push-events/.env | cut -d= -f2-")"
fi

fetch() {
  curl -fsS -H "Authorization: Bearer ${ADMIN_KEY}" "${BASE_URL}/v1/admin/dashboard.txt"
}

# Циклом с clear+sleep, а не watch(1) — на macOS его нет по умолчанию.
if [[ "${1:-}" == "--watch" ]]; then
  while true; do
    clear
    fetch
    sleep 10
  done
else
  fetch
fi
