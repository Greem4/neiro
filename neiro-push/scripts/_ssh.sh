#!/usr/bin/env bash
# SSH-хелпер для deploy/install скриптов neiro-push.
#
# С 19.10.2026 сервис живёт на VPS (roster-vps), и по умолчанию все скрипты
# ходят туда. Раньше хелпер сам выбирал Pi (roster-b3 / roster-pi-remote):
# после переезда запуск без переменной заливал код на Pi и оживлял старый
# сервис — два поллера слали бы одинаковые пуши.
#
# Pi — только для отката (docs/neiro-push/DEPLOY.md § Где живёт сейчас),
# и только явно: PI_SSH=roster-b3 ./neiro-push/scripts/deploy.sh
set -euo pipefail

NEIRO_PUSH_DEFAULT_SSH="roster-vps"
SSH_HOST="${PI_SSH:-${NEIRO_PUSH_DEFAULT_SSH}}"

ssh_pi() {
  ssh "${SSH_HOST}" "$@"
}
