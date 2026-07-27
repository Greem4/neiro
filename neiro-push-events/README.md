# neiro-push-events

FastAPI-сервис на Raspberry Pi: опрашивает YClients по всем подключённым
специалистам, превращает изменения записей в события (новая запись,
подтверждение, приход, отмена, перенос, удаление) и рассылает их устройствам
через FCM data-push. Публичный маршрут — `/v2` (см. §7 в
[docs/push-events.md](../docs/push-events.md)).

Полная архитектура, контракт и история разработки — в
[docs/push-events/](../docs/push-events/) (`plan.md`, `progress.md`).
Операционный документ (эксплуатация, дашборд, troubleshooting) —
[docs/push-events.md](../docs/push-events.md).

## Эндпоинты

| Метод | Путь | Ключ | Что делает |
|---|---|---|---|
| GET | `/health` | `ADMIN_API_KEY` | Статус сервиса: аптайм, число аккаунтов/устройств, события за сутки |
| POST | `/v1/devices/register` | `API_KEY` | Регистрация устройства; новому — курсор `max(events.id)`, известному — курсор не трогается |
| DELETE | `/v1/devices/{device_id}` | `API_KEY` | Снятие регистрации устройства |
| GET | `/v1/devices/{device_id}/events` | `API_KEY` | Догон: события после `since`, лимит `limit` (по умолчанию 100), `has_more` |
| POST | `/v1/devices/{device_id}/events/ack` | `API_KEY` | Подтверждение: двигает серверный курсор устройства |
| GET | `/v1/admin/events` | `ADMIN_API_KEY` | Последние события по всем аккаунтам (JSON) |
| GET | `/v1/admin/poll-log` | `ADMIN_API_KEY` | Последние циклы опроса (JSON) |
| GET | `/v1/admin/dashboard.txt` | `ADMIN_API_KEY` | Снимок дашборда простым текстом — для `curl`/`scripts/dash.sh` |
| GET | `/dashboard` | cookie после логина | HTML-дашборд, логин формой по `ADMIN_API_KEY` (cookie на 30 дней) |
| POST | `/dashboard` и `/dashboard/login` | `ADMIN_API_KEY` в поле `key` | Логин в HTML-дашборд; в ответ сразу страница, без редиректа |

`API_KEY` и `ADMIN_API_KEY` — разные ключи. `API_KEY` уходит в приложение
(`local.properties`), `ADMIN_API_KEY` — только себе, в приложении его нет.
Если `ADMIN_API_KEY` не задан, admin-эндпоинты принимают `API_KEY` (см.
`_admin_key` в [app/main.py](app/main.py)).

## Скрипты (`scripts/`)

| Скрипт | Что делает |
|---|---|
| `deploy.sh` | Синк кода на Pi, генерация `.env` при первом деплое, `docker compose up -d --build`, патч Caddy на Pi |
| `deploy-no-caddy.sh` | То же, но **без** шага с Caddy — им и деплоим: `/v2` идёт через VPS nginx, Caddy в маршруте не участвует (docs/push-events.md §7) |
| `logs.sh` | Логи контейнера: хвост, N строк, за период, только warning/error |
| `backup.sh` | Онлайн-бэкап SQLite (без остановки контейнера) + `.env` → `neiro-push-events/backups/` локально |
| `restore.sh` | Восстановление базы из бэкапа; снимает страховочный снимок перед подменой, останавливает контейнер на время подмены файла |
| `dash.sh` | Текстовый дашборд в терминал (`--watch` — обновление раз в 10 с) |
| `start-tunnel.sh` / `install-tunnel.sh` | Обратный SSH-туннель Pi → VPS для `/v2` (systemd `--user` юнит) |
| `patch-vps-nginx-v2.sh` | `location /v2/` в nginx на VPS, идемпотентно |
| `patch-pi-caddy-v2.py` | Написан по плану, **не используется** — реальный маршрут `/v2` идёт мимо Caddy на Pi (см. docs/push-events.md §7) |

## База данных

SQLite, Docker-том `neiro_push_events_data` → `/data/neiro_push_events.db` в
контейнере. Таблицы: `accounts`, `devices`, `record_states` (сидирование
диффа), `events` (журнал, сквозной `id` по всем аккаунтам), `push_deliveries`,
`poll_runs`. Схема — [app/database.py](app/database.py). Ретеншен —
`purge_old_data()`: события и доставки старше 30 дней, циклы опроса старше 7,
чистка после каждого `poll_once()`.

## Опрос YClients

Один HTTP-запрос на всю компанию (`fetch_company_records`), затем раскладка
записей по `staff_id` в памяти — см. [app/poller.py](app/poller.py). Интервал:

| Когда (МСК) | Интервал | Переменная |
|---|---|---|
| 09:00–23:00 (день) | 10 с | `POLL_INTERVAL_SECONDS` |
| 23:00–09:00 (ночь) | 3600 с | `POLL_NIGHT_INTERVAL_SECONDS` / `QUIET_START_HOUR` |

Backoff при ошибке компании — растёт `10 → 30 → 60 → 120 → 300 → 600 → 900` с,
сбрасывается на первом успешном опросе (`BACKOFF_STEPS_SECONDS` в `poller.py`).

## Переменные окружения (`.env`)

См. [.env.example](.env.example) — шаблон для копирования на Pi.

| Переменная | Назначение |
|---|---|
| `API_KEY` | Ключ устройств (регистрация, догон, ack) — уходит в приложение |
| `ADMIN_API_KEY` | Ключ дашборда и `/health` — только себе |
| `TOKEN_ENCRYPTION_KEY` | Fernet-ключ шифрования YClients-токенов в БД |
| `POLL_INTERVAL_SECONDS` / `POLL_NIGHT_INTERVAL_SECONDS` / `QUIET_START_HOUR` | Расписание опроса |
| `FCM_CREDENTIALS_PATH` | Путь к service account JSON (том `./secrets:/secrets:ro`) |
| `FCM_PROJECT_ID` | Project ID Firebase; если пусто — берётся из `project_id` в самом JSON |
| `DATABASE_PATH` | Путь к SQLite внутри контейнера |
| `LOG_LEVEL` | Уровень логирования uvicorn/приложения |

## Тесты

```bash
python3 -m venv /tmp/venv-neiro-events
/tmp/venv-neiro-events/bin/pip install pytest httpx pydantic-settings \
    google-auth cryptography requests fastapi jinja2 uvicorn
cd neiro-push-events && PYTHONPATH=. /tmp/venv-neiro-events/bin/python -m pytest tests/ -v
```
