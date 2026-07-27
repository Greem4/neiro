#!/usr/bin/env bash
# Бэкап neiro-push-events: база SQLite + .env с Pi на локальную машину.
# Запускать ПЕРЕД любым деплоем со схемой/миграциями.
#
#   ./neiro-push-events/scripts/backup.sh          # снять бэкап
#   ./neiro-push-events/scripts/backup.sh --list    # показать, что уже снято
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_DIR="${ROOT_DIR}/backups"
REMOTE_DIR="neiro-push-events-backups"
# shellcheck source=./_ssh.sh
source "${ROOT_DIR}/scripts/_ssh.sh"

if [[ "${1:-}" == "--list" ]]; then
  echo "Локально (${LOCAL_DIR}):"
  ls -lh "${LOCAL_DIR}" 2>/dev/null || echo "  (пусто)"
  echo ""
  echo "На Pi (~/${REMOTE_DIR}):"
  ssh_pi "ls -lh ~/${REMOTE_DIR} 2>/dev/null || echo '  (пусто)'"
  exit 0
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
echo "Бэкап neiro-push-events → ${STAMP}"

# Онлайн-бэкап через sqlite3 backup API: консистентный снимок,
# контейнер при этом можно не останавливать.
ssh_pi env STAMP="${STAMP}" REMOTE_DIR="${REMOTE_DIR}" bash -s <<'REMOTE'
set -euo pipefail
cd ~/neiro-push-events
mkdir -p ~/"${REMOTE_DIR}"

docker compose exec -T neiro-push-events python - <<'PY'
import sqlite3
src = sqlite3.connect("/data/neiro_push_events.db")
dst = sqlite3.connect("/data/_backup_tmp.db")
with dst:
    src.backup(dst)
dst.close()
src.close()
print("sqlite backup ok")
PY

docker compose cp neiro-push-events:/data/_backup_tmp.db ~/"${REMOTE_DIR}/neiro_push_events-${STAMP}.db"
docker compose exec -T neiro-push-events rm -f /data/_backup_tmp.db < /dev/null

cp .env ~/"${REMOTE_DIR}/env-${STAMP}.txt"
chmod 600 ~/"${REMOTE_DIR}/env-${STAMP}.txt"

# На Pi держим только 10 последних снимков — SD-карта не резиновая.
ls -1t ~/"${REMOTE_DIR}"/neiro_push_events-*.db 2>/dev/null | tail -n +11 | xargs -r rm -f
ls -1t ~/"${REMOTE_DIR}"/env-*.txt 2>/dev/null | tail -n +11 | xargs -r rm -f

echo "на Pi: ~/${REMOTE_DIR}/neiro_push_events-${STAMP}.db"
REMOTE

mkdir -p "${LOCAL_DIR}"
scp -q "${SSH_HOST}:~/${REMOTE_DIR}/neiro_push_events-${STAMP}.db" "${LOCAL_DIR}/"
scp -q "${SSH_HOST}:~/${REMOTE_DIR}/env-${STAMP}.txt" "${LOCAL_DIR}/"
chmod 600 "${LOCAL_DIR}/env-${STAMP}.txt"

echo ""
echo "Готово:"
echo "  ${LOCAL_DIR}/neiro_push_events-${STAMP}.db"
echo "  ${LOCAL_DIR}/env-${STAMP}.txt   (секреты, chmod 600, в git не попадает)"
echo ""
echo "Откат базы:  ./neiro-push-events/scripts/restore.sh neiro_push_events-${STAMP}.db"
