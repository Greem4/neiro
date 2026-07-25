#!/usr/bin/env bash
# Восстановление базы neiro-push-events из бэкапа, снятого backup.sh.
#
#   ./neiro-push-events/scripts/restore.sh neiro_push_events-20260725-181500.db
#
# Контейнер останавливается на время подмены файла — иначе SQLite
# может писать в старый inode и затереть восстановленное.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_DIR="${ROOT_DIR}/backups"
REMOTE_DIR="neiro-push-events-backups"
# shellcheck source=./_ssh.sh
source "${ROOT_DIR}/scripts/_ssh.sh"

BACKUP_NAME="${1:-}"
if [[ -z "${BACKUP_NAME}" ]]; then
  echo "Укажи файл бэкапа. Доступные:"
  ls -1t "${LOCAL_DIR}"/neiro_push_events-*.db 2>/dev/null | xargs -r -n1 basename || echo "  (пусто)"
  exit 1
fi

LOCAL_FILE="${LOCAL_DIR}/${BACKUP_NAME}"
if [[ ! -f "${LOCAL_FILE}" ]]; then
  echo "Нет файла: ${LOCAL_FILE}" >&2
  exit 1
fi

echo "Восстановить ${BACKUP_NAME} на ${SSH_HOST}?"
read -r -p "Текущая база будет заменена. Продолжить? [y/N] " answer
[[ "${answer}" == "y" || "${answer}" == "Y" ]] || { echo "Отменено."; exit 0; }

scp -q "${LOCAL_FILE}" "${SSH_HOST}:~/${REMOTE_DIR}/_restore.db"

ssh_pi env REMOTE_DIR="${REMOTE_DIR}" bash -s <<'REMOTE'
set -euo pipefail
cd ~/neiro-push-events

# Страховка: снимок текущей базы перед подменой.
SAFETY="$(date +%Y%m%d-%H%M%S)-before-restore"
docker compose exec -T neiro-push-events python - <<'PY'
import sqlite3
src = sqlite3.connect("/data/neiro_push_events.db")
dst = sqlite3.connect("/data/_safety_tmp.db")
with dst:
    src.backup(dst)
dst.close()
src.close()
PY
docker compose cp neiro-push-events:/data/_safety_tmp.db ~/"${REMOTE_DIR}/neiro_push_events-${SAFETY}.db"
docker compose exec -T neiro-push-events rm -f /data/_safety_tmp.db < /dev/null
echo "страховочный снимок: ~/${REMOTE_DIR}/neiro_push_events-${SAFETY}.db"

docker compose stop neiro-push-events
docker compose cp ~/"${REMOTE_DIR}/_restore.db" neiro-push-events:/data/neiro_push_events.db
# WAL/SHM от прошлой сессии не должны пережить подмену файла.
docker compose run --rm --no-deps -T neiro-push-events rm -f /data/neiro_push_events.db-wal /data/neiro_push_events.db-shm 2>/dev/null || true
docker compose start neiro-push-events
rm -f ~/"${REMOTE_DIR}/_restore.db"

sleep 5
curl -fsS http://127.0.0.1:8011/health
echo
REMOTE

echo ""
echo "База восстановлена из ${BACKUP_NAME}."
