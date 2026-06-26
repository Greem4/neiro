#!/usr/bin/env bash
# Health и список устройств (только admin key).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=./_ssh.sh
source "${ROOT_DIR}/scripts/_ssh.sh"

NEIRO_PUSH_PUBLIC_HOST="${NEIRO_PUSH_PUBLIC_HOST:-push.neiro.greemlab.ru}"
BASE_URL="https://${NEIRO_PUSH_PUBLIC_HOST}"

if [[ -n "${NEIRO_PUSH_ADMIN_KEY:-}" ]]; then
  ADMIN_KEY="${NEIRO_PUSH_ADMIN_KEY}"
else
  ADMIN_KEY="$(ssh_pi "grep ^ADMIN_API_KEY= ~/neiro-push/.env | cut -d= -f2-")"
fi

AUTH=(-H "Authorization: Bearer ${ADMIN_KEY}")

case "${1:-health}" in
  health)
    curl -fsS "${BASE_URL}/health" "${AUTH[@]}"
    echo
    ;;
  overview)
    curl -fsS "${BASE_URL}/v1/admin/overview" "${AUTH[@]}" | python3 -m json.tool
    ;;
  *)
    echo "Usage: $0 [health|overview]" >&2
    exit 1
    ;;
esac
