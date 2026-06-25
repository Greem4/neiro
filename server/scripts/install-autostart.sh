#!/usr/bin/env bash
# Добавляет neiro-push в @reboot crontab на Pi (без правок Roster).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=./_ssh.sh
source "${ROOT_DIR}/scripts/_ssh.sh"

MARKER="neiro-push compose up"
CRON_LINE='@reboot sleep 60 && cd ~/neiro-push && docker compose up -d >>~/neiro-push/autostart.log 2>&1'

ssh_pi bash -s <<REMOTE
set -euo pipefail
if crontab -l 2>/dev/null | grep -q '${MARKER}'; then
  echo "autostart already installed"
  exit 0
fi
(crontab -l 2>/dev/null; echo "# ${MARKER}"; echo "${CRON_LINE}") | crontab -
echo "autostart installed"
REMOTE
