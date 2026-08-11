#!/usr/bin/env bash
# Локальный запуск neiro-push (и дашборда) в Docker для разработки —
# одна команда, живой код через volume, автоперезагрузка uvicorn.
#
#   ./neiro-push/scripts/dev.sh            # запуск (засеет базу при первом разе)
#   ./neiro-push/scripts/dev.sh --reset     # пересоздать тестовые данные с нуля
#
# Дашборд: http://127.0.0.1:8012/dashboard
#
# .env берётся как есть (см. .env.example) — сюда не лезем. Реального
# YClients с локальной машины не трогаем: если в .env для YCLIENTS_BASE_URL
# нет заглушки на недоступный локальный порт, поллер (тот же процесс) будет
# опрашивать настоящий YClients тестовыми аккаунтами из сидера.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

if [[ ! -f .env ]]; then
  echo "Нет neiro-push/.env — создайте его (см. .env.example) перед запуском." >&2
  exit 1
fi

mkdir -p data

if [[ "${1:-}" == "--reset" ]]; then
  echo "Сбрасываю тестовые данные..."
  rm -f data/neiro_push.db data/neiro_push.db-wal data/neiro_push.db-shm
fi

if [[ ! -f data/neiro_push.db ]]; then
  echo "Наполняю базу тестовыми данными..."
  docker compose -f docker-compose.dev.yml run --rm neiro-push python scripts/seed_dev_data.py
fi

echo ""
echo "Дашборд: http://127.0.0.1:8012/dashboard"
echo ""

docker compose -f docker-compose.dev.yml up --build
