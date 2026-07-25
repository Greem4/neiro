#!/usr/bin/env bash
# Логи neiro-push-events с Pi.
#
#   ./neiro-push-events/scripts/logs.sh              # хвост в реальном времени
#   ./neiro-push-events/scripts/logs.sh 500          # последние 500 строк и выход
#   ./neiro-push-events/scripts/logs.sh --since 1h   # за последний час и выход
#   ./neiro-push-events/scripts/logs.sh --errors     # только warning/error за сутки
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=./_ssh.sh
source "${ROOT_DIR}/scripts/_ssh.sh"

case "${1:-}" in
  --errors)
    ssh_pi "cd ~/neiro-push-events && docker compose logs --since 24h --no-color neiro-push-events" \
      | grep -Ei 'warning|error|exception|traceback' || echo "Ошибок за сутки нет."
    ;;
  --since)
    ssh_pi "cd ~/neiro-push-events && docker compose logs --since ${2:?укажи период, например 1h} neiro-push-events"
    ;;
  "")
    echo "Хвост логов (Ctrl+C — выход)"
    ssh_pi -t "cd ~/neiro-push-events && docker compose logs -f --tail=200 neiro-push-events"
    ;;
  *)
    ssh_pi "cd ~/neiro-push-events && docker compose logs --tail=${1} neiro-push-events"
    ;;
esac
