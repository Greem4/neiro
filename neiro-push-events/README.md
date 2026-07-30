# neiro-push-events

FastAPI-сервис на Raspberry Pi: опрашивает YClients по всем подключённым
специалистам, превращает изменения записей в события (новая запись,
подтверждение, приход, отмена, перенос, удаление) и рассылает их устройствам
через FCM data-push.

## Старт

Обе команды запускаются **из корня репозитория** — никуда переходить не нужно.

```bash
./neiro-push-events/scripts/dev.sh            # локально
./neiro-push-events/scripts/dev.sh --reset    # локально, с чистыми тестовыми данными
```

Дашборд — `http://127.0.0.1:8011/dashboard`. Код и шаблоны подхватываются на
лету, пересобирать контейнер не нужно.

## Деплой

```bash
./neiro-push-events/scripts/deploy.sh
```

Одна команда, из любой сети. Как устроен публичный маршрут `/v2`, откуда
деплоить и что делать, если адрес молчит — [docs/deploy.md](../docs/deploy.md).

Архитектура и история разработки — [plan.md](../docs/push-events/plan.md) и
[progress.md](../docs/push-events/progress.md). Эксплуатация и troubleshooting —
[docs/push-events.md](../docs/push-events.md).

## Эндпоинты

| Метод  | Путь                                 | Ключ                         | Что делает                                                                                 |
|--------|--------------------------------------|------------------------------|--------------------------------------------------------------------------------------------|
| GET    | `/health`                            | `ADMIN_API_KEY`              | Статус сервиса: аптайм, число аккаунтов/устройств, события за сутки                        |
| POST   | `/v1/devices/register`               | `API_KEY`                    | Регистрация устройства; новому — курсор `max(events.id)`, известному — курсор не трогается |
| DELETE | `/v1/devices/{device_id}`            | `API_KEY`                    | Снятие регистрации устройства                                                              |
| GET    | `/v1/devices/{device_id}/events`     | `API_KEY`                    | Догон: события после `since`, лимит `limit` (по умолчанию 100), `has_more`                 |
| POST   | `/v1/devices/{device_id}/events/ack` | `API_KEY`                    | Подтверждение: двигает серверный курсор устройства                                         |
| GET    | `/v1/admin/events`                   | `ADMIN_API_KEY`              | Последние события по всем аккаунтам (JSON)                                                 |
| GET    | `/v1/admin/poll-log`                 | `ADMIN_API_KEY`              | Последние циклы опроса (JSON)                                                              |
| GET    | `/v1/admin/dashboard.txt`            | `ADMIN_API_KEY`              | Снимок дашборда простым текстом — для `curl`/`scripts/dash.sh`                             |
| GET    | `/dashboard`                         | cookie после логина          | HTML-дашборд, логин формой по `ADMIN_API_KEY` (cookie на 30 дней)                          |
| POST   | `/dashboard` и `/dashboard/login`    | `ADMIN_API_KEY` в поле `key` | Логин в HTML-дашборд; в ответ сразу страница, без редиректа                                |

`API_KEY` и `ADMIN_API_KEY` — разные ключи. `API_KEY` уходит в приложение
(`local.properties`), `ADMIN_API_KEY` — только себе, в приложении его нет.
`ADMIN_API_KEY` обязателен: без него сервис не стартует. Раньше при пустом
значении admin-эндпоинты принимали `API_KEY` — тот самый, что лежит в APK.

## Скрипты (`scripts/`)

| Скрипт                                  | Что делает                                                                                                                 |
|-----------------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| `deploy.sh`                             | Деплой на Pi целиком: синк кода, `.env` при первом разе, пересборка контейнера, подъём туннеля, проверка публичного адреса |
| `dev.sh`                                | Локальный запуск в Docker; `--reset` — пересоздать тестовые данные                                                         |
| `logs.sh`                               | Логи контейнера: хвост, N строк, за период, только warning/error                                                           |
| `backup.sh`                             | Онлайн-бэкап SQLite (без остановки контейнера) + `.env` → `neiro-push-events/backups/` локально                            |
| `restore.sh`                            | Восстановление базы из бэкапа; снимает страховочный снимок перед подменой, останавливает контейнер на время подмены файла  |
| `dash.sh`                               | Текстовый дашборд в терминал (`--watch` — обновление раз в 10 с)                                                           |
| `seed_dev_data.py`                      | Тестовые данные для локального дашборда; вызывается из `dev.sh`                                                            |
| `start-tunnel.sh` / `install-tunnel.sh` | Обратный SSH-туннель Pi → VPS для `/v2` (systemd `--user` юнит)                                                            |
| `patch-vps-nginx-v2.sh`                 | `location /v2/` в nginx на VPS, идемпотентно                                                                               |

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

| Когда (МСК)        | Интервал | Переменная                                         |
|--------------------|----------|----------------------------------------------------|
| 09:00–23:00 (день) | 10 с     | `POLL_INTERVAL_SECONDS`                            |
| 23:00–09:00 (ночь) | 3600 с   | `POLL_NIGHT_INTERVAL_SECONDS` / `QUIET_START_HOUR` |

Backoff при ошибке компании — растёт `10 → 30 → 60 → 120 → 300 → 600 → 900` с,
сбрасывается на первом успешном опросе (`BACKOFF_STEPS_SECONDS` в `poller.py`).

## Переменные окружения (`.env`)

См. [.env.example](.env.example) — шаблон для копирования на Pi.

| Переменная                                                                   | Назначение                                                             |
|------------------------------------------------------------------------------|------------------------------------------------------------------------|
| `API_KEY`                                                                    | Ключ устройств (регистрация, догон, ack) — уходит в приложение         |
| `ADMIN_API_KEY`                                                              | Ключ дашборда и `/health` — только себе                                |
| `TOKEN_ENCRYPTION_KEY`                                                       | Fernet-ключ шифрования YClients-токенов в БД                           |
| `POLL_INTERVAL_SECONDS` / `POLL_NIGHT_INTERVAL_SECONDS` / `QUIET_START_HOUR` | Расписание опроса                                                      |
| `FCM_CREDENTIALS_PATH`                                                       | Путь к service account JSON (том `./secrets:/secrets:ro`)              |
| `FCM_PROJECT_ID`                                                             | Project ID Firebase; если пусто — берётся из `project_id` в самом JSON |
| `DATABASE_PATH`                                                              | Путь к SQLite внутри контейнера                                        |
| `LOG_LEVEL`                                                                  | Уровень логирования uvicorn/приложения                                 |

## Тесты

Только через Docker, в том же образе, что и прод — ничего ставить на машину не
нужно.

```bash
cd "$(git rev-parse --show-toplevel)"
docker run --rm -v "$PWD":/repo -w /repo/neiro-push-events python:3.12-slim \
  sh -c "pip install -q pytest && pip install -q -r requirements.txt && python -m pytest -q"
```

Монтировать нужно **корень репозитория**, а не папку сервиса: `tests/test_yclients.py`
читает фикстуры из `tools/yclients-sandbox/exports/` уровнем выше — иначе девять
тестов упадут на `FileNotFoundError`.
