#!/usr/bin/env bash
# Отзыв device_token одной командой. Кнопка в дашборде тоже есть, но в момент,
# когда она понадобится, обычно быстрее набрать это.
#
#   ./neiro-push/scripts/revoke-device.sh                 # список устройств
#   ./neiro-push/scripts/revoke-device.sh <device_id>     # отозвать
#   ./neiro-push/scripts/revoke-device.sh --reset <id>    # потребовать вход паролем
#
# После отзыва телефон получает 401 на первом же запросе и уходит в локальный
# режим: календарь, архив и деньги работают, свежих данных из YClients нет.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=./_ssh.sh
source "${ROOT_DIR}/scripts/_ssh.sh"

ADMIN_KEY="$(ssh_pi "grep ^ADMIN_API_KEY= ~/neiro-push/.env | cut -d= -f2-")"
BASE="http://127.0.0.1:8012"

api() {
  ssh_pi "curl -fsS -X ${1} -H 'Authorization: Bearer ${ADMIN_KEY}' '${BASE}${2}'"
}

case "${1:-}" in
  "")
    echo "Устройства:"
    ssh_pi "curl -fsS -H 'Authorization: Bearer ${ADMIN_KEY}' '${BASE}/v1/admin/dashboard.txt'" \
      | sed -n '/[Уу]стройств/,$p'
    echo
    echo "Отозвать:  $0 <device_id>"
    ;;
  --reset)
    ACCOUNT_ID="${2:?укажи account_id}"
    api POST "/v1/admin/accounts/${ACCOUNT_ID}/reset"
    echo
    echo "Аккаунту ${ACCOUNT_ID} потребуется повторный вход паролем."
    ;;
  *)
    DEVICE_ID="$1"
    api POST "/v1/admin/devices/${DEVICE_ID}/revoke"
    echo
    echo "Устройство ${DEVICE_ID} отозвано: его device_token больше не работает."
    ;;
esac
