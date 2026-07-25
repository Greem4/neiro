#!/usr/bin/env bash
# Логи neiro-push с Pi.
#
#   ./server/scripts/logs.sh              # хвост в реальном времени
#   ./server/scripts/logs.sh 500          # последние 500 строк и выход
#   ./server/scripts/logs.sh --since 1h   # за последний час и выход
#   ./server/scripts/logs.sh --errors     # только warning/error за сутки
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=./_ssh.sh
source "${ROOT_DIR}/scripts/_ssh.sh"

case "${1:-}" in
  --errors)
    ssh_pi "cd ~/neiro-push && docker compose logs --since 24h --no-color neiro-push" \
      | grep -Ei 'warning|error|exception|traceback' || echo "Ошибок за сутки нет."
    ;;
  --since)
    ssh_pi "cd ~/neiro-push && docker compose logs --since ${2:?укажи период, например 1h} neiro-push"
    ;;
  "")
    echo "Хвост логов (Ctrl+C — выход)"
    ssh_pi -t "cd ~/neiro-push && docker compose logs -f --tail=200 neiro-push"
    ;;
  *)
    ssh_pi "cd ~/neiro-push && docker compose logs --tail=${1} neiro-push"
    ;;
esac
