#!/usr/bin/env bash
# Тестовый FCM push на все устройства (или одно: --device-id ...).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=./_ssh.sh
source "${ROOT_DIR}/scripts/_ssh.sh"

NEIRO_PUSH_PUBLIC_HOST="${NEIRO_PUSH_PUBLIC_HOST:-push.neiro.greemlab.ru}"
BASE_URL="https://${NEIRO_PUSH_PUBLIC_HOST}"

if [[ -n "${NEIRO_PUSH_ADMIN_KEY:-}" ]]; then
  ADMIN_KEY="${NEIRO_PUSH_ADMIN_KEY}"
elif [[ -n "${1:-}" && "${1}" != --* ]]; then
  ADMIN_KEY="$1"
  shift
else
  ADMIN_KEY="$(ssh_pi "grep ^ADMIN_API_KEY= ~/neiro-push/.env | cut -d= -f2-")"
fi

DEVICE_ID=""
ACCOUNT_ID=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --device-id)
      DEVICE_ID="$2"
      shift 2
      ;;
    --account-id)
      ACCOUNT_ID="$2"
      shift 2
      ;;
    *)
      echo "Unknown arg: $1" >&2
      exit 1
      ;;
  esac
done

BODY='{}'
if [[ -n "$DEVICE_ID" ]]; then
  BODY=$(python3 -c "import json; print(json.dumps({'device_id': '$DEVICE_ID'}))")
elif [[ -n "$ACCOUNT_ID" ]]; then
  BODY=$(python3 -c "import json; print(json.dumps({'account_id': $ACCOUNT_ID}))")
fi

echo "POST ${BASE_URL}/v1/admin/test-push"
curl -fsS -X POST "${BASE_URL}/v1/admin/test-push" \
  -H "Authorization: Bearer ${ADMIN_KEY}" \
  -H "Content-Type: application/json" \
  -d "${BODY}"
echo
