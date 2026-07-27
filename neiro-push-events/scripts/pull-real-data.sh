#!/usr/bin/env bash
# Переносит реальный аккаунт с Pi в ЛОКАЛЬНУЮ базу — чтобы править дашборд на
# настоящих данных: длинные имена клиентов, реальные типы событий, реальные
# статусы доставки.
#
#   ./neiro-push-events/scripts/pull-real-data.sh            # компания по умолчанию
#   ./neiro-push-events/scripts/pull-real-data.sh 520135     # конкретная компания
#
# Добавляет данные РЯДОМ с тестовыми из seed_dev_data.py, не затирая их: в
# локальной базе получаются две компании — синтетическая и настоящая. Повторный
# запуск заменяет ранее перенесённую компанию, дублей не будет.
#
# Что НЕ переносится (намеренно):
#   * YClients-токены  — на Pi они зашифрованы боевым ключом, локально он другой.
#                        Кладём заглушки, зашифрованные локальным ключом.
#   * FCM-токен устройства — иначе локальный запуск мог бы отправить пуш на
#                        реальный телефон.
#
# ВАЖНО: в перенесённых событиях лежат настоящие имена клиентов. Локальная база
# (neiro-push-events/data/) в .gitignore — держите эти данные там и не коммитьте.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "${ROOT_DIR}/.." && pwd)"
COMPANY_ID="${1:-520135}"
DUMP="${ROOT_DIR}/data/_real_dump.json"
# shellcheck source=./_ssh.sh
source "${ROOT_DIR}/scripts/_ssh.sh"

if [[ ! -f "${ROOT_DIR}/data/neiro_push_events.db" ]]; then
  echo "Нет локальной базы. Сначала подними локально:" >&2
  echo "  ./neiro-push-events/scripts/dev.sh" >&2
  exit 1
fi

mkdir -p "${ROOT_DIR}/data"
echo "Забираю компанию ${COMPANY_ID} с ${SSH_HOST}..."

ssh_pi env COMPANY_ID="${COMPANY_ID}" bash -s > "${DUMP}" <<'REMOTE'
set -euo pipefail
cd ~/neiro-push-events
docker compose exec -T -e COMPANY_ID="${COMPANY_ID}" neiro-push-events python - <<'PY'
import json, os, sqlite3

company_id = int(os.environ["COMPANY_ID"])
conn = sqlite3.connect("/data/neiro_push_events.db")
conn.row_factory = sqlite3.Row
rows = lambda q, p=(): [dict(r) for r in conn.execute(q, p)]

accounts = rows("SELECT * FROM accounts WHERE company_id = ?", (company_id,))
ids = [a["id"] for a in accounts]
marks = ",".join("?" for _ in ids) or "NULL"

devices = rows(f"SELECT * FROM devices WHERE account_id IN ({marks})", ids)
events = rows(f"SELECT * FROM events WHERE account_id IN ({marks}) ORDER BY id", ids)
event_ids = [e["id"] for e in events]
emarks = ",".join("?" for _ in event_ids) or "NULL"
deliveries = rows(
    f"SELECT * FROM push_deliveries WHERE event_id IN ({emarks}) ORDER BY id", event_ids
)
poll_runs = rows(
    "SELECT * FROM poll_runs WHERE company_id = ? ORDER BY id DESC LIMIT 100", (company_id,)
)

print(json.dumps({
    "company_id": company_id,
    "accounts": accounts,
    "devices": devices,
    "events": events,
    "deliveries": deliveries,
    "poll_runs": poll_runs,
}, ensure_ascii=False))
PY
REMOTE

# Импорт — в контейнере: нужен cryptography для шифрования заглушек-токенов
# локальным ключом, а на машину мы ничего не ставим.
docker run --rm \
  -v "${REPO_ROOT}":/repo -w /repo/neiro-push-events \
  --env-file "${ROOT_DIR}/.env" \
  -e DUMP_PATH=/repo/neiro-push-events/data/_real_dump.json \
  -e DATABASE_PATH=/repo/neiro-push-events/data/neiro_push_events.db \
  python:3.12-slim \
  sh -c "pip install -q -r requirements.txt >/dev/null 2>&1 && python scripts/import_real_data.py"

rm -f "${DUMP}"
echo ""
echo "Готово. Дашборд: http://127.0.0.1:8011/dashboard"
echo "Если сервис уже запущен — просто обнови страницу."
