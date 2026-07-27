# Сервис событий push — эксплуатация

Операционный документ: как сервис устроен, как зайти в дашборд, как читать
логи и что делать, если уведомление не дошло. История разработки, контракт и
решения по ходу — в [docs/push-events/](push-events/) (`plan.md`,
`progress.md`); детали приложения — [docs/push-events/app.md](push-events/app.md).

## 1. Как устроено

```
YClients ──опрос 10с/1ч──▶ neiro-push-events (Pi, порт 8011)
                                   │
                     дифф с прошлым состоянием записи
                     (record_states → events, §events.py)
                                   │
                     ┌─────────────┴─────────────┐
                     │                            │
              FCM data-push                  журнал events
         {action: session_events,           (переживает рестарт,
          events: [...], last_event_id}      видно в дашборде)
                     │
                     ▼
   Телефон: NeiroFirebaseMessagingService
       ├─ отсев чужого staff_id
       ├─ PushEventCalendarApplier → правит календарь без похода в YClients
       └─ PushEventNotifier → показывает уведомление
```

Если пуш не долетел (Doze, «Принудительная остановка», payload > 3 КБ) —
телефон догоняет журнал сам: keepalive-тик раз в 30–60 мин, открытие
приложения, либо нудж `sync_events` от сервера. Догон работает по курсору
`last_event_id`, который сервер помнит на устройство (`devices.last_ack_event_id`)
и подтверждает `POST /v1/devices/{id}/events/ack`.

Подробный контракт полей, правила диффа (кто с кем сливается, что считается
переносом) и разбор per-этапной реализации — [plan.md](push-events/plan.md) и
[progress.md](push-events/progress.md).

## 2. Схема БД

SQLite, файл `/data/neiro_push_events.db` в контейнере (Docker-том
`neiro_push_events_data`). Определение — [../neiro-push-events/app/database.py](../neiro-push-events/app/database.py).

| Таблица | Назначение |
|---|---|
| `accounts` | Один ряд на пару `(company_id, staff_id)` — зашифрованные YClients-токены, курсор `changed_after`, состояние backoff |
| `devices` | Зарегistrированные телефоны: `fcm_token`, `label`, `app_version`, курсор `last_ack_event_id` |
| `record_states` | Последнее известное состояние каждой записи YClients — с чем сравнивать на следующем опросе (дифф). Пусто у аккаунта = следующий опрос сидирующий, событий не будет |
| `events` | Журнал: тип, клиент, дата/время, `prev_date`/`prev_time` для переноса. `id` сквозной по всем аккаунтам — это и есть курсор устройства |
| `push_deliveries` | Кто из устройств получил какое событие: `sent` / `failed` / `token_invalid` |
| `poll_runs` | Один ряд на цикл опроса компании: длительность, сколько записей/событий/пушей, ошибка если была |

Ретеншен (`purge_old_data`, после каждого `poll_once`): `events` и
`push_deliveries` старше 30 дней, `poll_runs` старше 7 — удаляются
безвозвратно, если нужна более долгая история, снимайте `backup.sh`.

## 3. Дашборд

### Способ 1 — браузер (с телефона тоже)

```
https://push.neiro.greemlab.ru/v2/dashboard
```

Логин формой — вводите `ADMIN_API_KEY`, дальше 30 дней держит HttpOnly cookie.
Показывает: статус (FCM/аптайм/последняя ошибка), последние 50 событий с
доставкой, аккаунты (backoff/ошибки), устройства (курсор, когда виделись),
последние 20 циклов опроса. Обновление раз в 10 с (`meta refresh`), без JS.

**Время на дашборде — московское.** В базе оно хранится в UTC (по нему считаются
ретеншен и «за сутки»), а на экран переводится в МСК — в шапке об этом есть
пометка. В логах контейнера (`logs.sh`) время остаётся **UTC**: это вывод
uvicorn, он не переводится. Разница в 3 часа между дашбордом и логом — норма.

`ADMIN_API_KEY`:

```bash
ssh roster-b3 'grep ^ADMIN_API_KEY= ~/neiro-push-events/.env'
```

### Способ 2 — терминал

```bash
./neiro-push-events/scripts/dash.sh            # разовый снимок
./neiro-push-events/scripts/dash.sh --watch     # обновление раз в 10 с (Ctrl+C — выход)
```

Ключ скрипт достаёт сам по ssh; можно подставить свой через
`NEIRO_PUSH_EVENTS_ADMIN_KEY=... ./neiro-push-events/scripts/dash.sh`. Тот же
снимок и вручную:

```bash
curl -H "Authorization: Bearer $(ssh roster-b3 'grep ^ADMIN_API_KEY= ~/neiro-push-events/.env | cut -d= -f2-')" \
  https://push.neiro.greemlab.ru/v2/v1/admin/dashboard.txt
```

### Сырые данные (JSON)

```bash
curl -H "Authorization: Bearer <ADMIN_API_KEY>" https://push.neiro.greemlab.ru/v2/v1/admin/events?limit=50
curl -H "Authorization: Bearer <ADMIN_API_KEY>" https://push.neiro.greemlab.ru/v2/v1/admin/poll-log?limit=50
curl -H "Authorization: Bearer <ADMIN_API_KEY>" https://push.neiro.greemlab.ru/v2/health
```

## 4. Логи

```bash
./neiro-push-events/scripts/logs.sh              # хвост в реальном времени
./neiro-push-events/scripts/logs.sh 500          # последние 500 строк
./neiro-push-events/scripts/logs.sh --since 1h   # за последний час
./neiro-push-events/scripts/logs.sh --errors     # только warning/error за сутки
```

Формат строк — [plan.md §8.1](push-events/plan.md):

```
INFO  poll company=123 records=3 events=1 pushes=2 duration=412ms
INFO  event id=1234 CLIENT_CONFIRMED "Иванов Ваня" 2026-07-26 15:00 → 2 devices
WARN  push failed device=neiro-Pixel-… : UNAVAILABLE
INFO  backoff company=123 until=12:41:05 (errors=3)
```

**Сервис жив, если:** `logs.sh --errors` пуст за последние сутки, `/health`
отвечает `200` и `poll_health_summary.last_polled_at` не старше пары минут
днём (10 с интервал) или часа ночью.

## 5. Переменные окружения

`../neiro-push-events/.env` на Pi (`~/neiro-push-events/.env`, шаблон —
[.env.example](../neiro-push-events/.env.example)):

| Переменная | Что меняет |
|---|---|
| `API_KEY` | Ключ устройств — регистрация, догон, ack. Уходит в приложение (`local.properties` → `NEIRO_PUSH_API_KEY`) |
| `ADMIN_API_KEY` | Ключ дашборда, `/health`, admin-JSON-эндпоинтов. Никогда не в приложении |
| `TOKEN_ENCRYPTION_KEY` | Fernet-ключ, которым шифруются YClients partner/user token в `accounts`. Смена ключа делает старые записи нерасшифровываемыми — переносить осторожно |
| `POLL_INTERVAL_SECONDS` | Интервал опроса днём (по умолчанию 10 с) |
| `POLL_NIGHT_INTERVAL_SECONDS` / `QUIET_START_HOUR` | Интервал ночью и час, с которого действует ночной режим (МСК) |
| `FCM_CREDENTIALS_PATH` | Путь к service account JSON внутри контейнера (`secrets/`, том read-only) |
| `FCM_PROJECT_ID` | Project ID Firebase; пусто — берётся из самого JSON |
| `DATABASE_PATH` | Путь к SQLite внутри контейнера |
| `LOG_LEVEL` | Уровень логирования |

Генерация новых ключей (при первом деплое делает `deploy.sh` сам):

```bash
python3 -c "import secrets; print(secrets.token_urlsafe(32))"
python3 -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```

## 6. Бэкап и восстановление

**Когда снимать:** перед любым деплоем со сменой схемы БД, и не помешает
хотя бы раз в неделю в спокойном режиме.

```bash
./neiro-push-events/scripts/backup.sh            # снять: sqlite-снимок + .env
./neiro-push-events/scripts/backup.sh --list     # что уже снято (локально и на Pi)
```

Бэкап — консистентный онлайн-снимок через `sqlite3` backup API, контейнер не
останавливается. Локально складывается в `neiro-push-events/backups/`
(`../neiro-push-events/.env`-снимок — с правами `600`, в git не попадает). На Pi хранится 10
последних снимков.

**Откат:**

```bash
./neiro-push-events/scripts/restore.sh neiro_push_events-20260726-181500.db
```

Спросит подтверждение, сам снимет страховочный снимок текущей базы перед
подменой, на время подмены остановит контейнер (иначе SQLite может писать в
старый inode поверх восстановленного файла), затем запустит обратно и
проверит `/health`.

## 7. Сеть и маршрут `/v2`

Публичный URL `https://push.neiro.greemlab.ru/v2` идёт **не** через Caddy на
Pi (несмотря на то что `plan.md` изначально предполагал `handle_path` в
Caddy — расхождение задокументировано в
[progress.md, подводный камень 2](push-events/progress.md)):

```
https://push.neiro.greemlab.ru
        │
VPS 176.12.65.86 — nginx + Let's Encrypt
        ├─ location /v2/  →  127.0.0.1:18082
        └─ location /     →  127.0.0.1:18080
                │  обратные SSH-туннели, поднимает Pi
Raspberry Pi
        ├─ 18082 → 127.0.0.1:8011   neiro-push-events (новый)
        └─ 18080 → 127.0.0.1:8010   neiro-push (старый, см. push-setup.md)
```

Туннель — systemd `--user` юнит `neiro-push-events-tunnel.service` на Pi
(`scripts/start-tunnel.sh` + `scripts/install-tunnel.sh`). `scripts/patch-pi-caddy-v2.py`
существует, но не используется — оставлен как задокументированная попытка «по
плану», которая не подошла реальной топологии сети.

## 8. Что делать, если уведомление не пришло

По шагам, от простого к сложному:

1. **Событие вообще возникло?** Откройте дашборд →
   [лента событий](#3-дашборд). Есть нужное событие (клиент, дата/время,
   тип) — идите к шагу 3. Нет — к шагу 2.
2. **Событие не создалось.** Смотрите блок «Циклы опроса» и
   `poll-log.sh --errors` / `/v1/admin/poll-log`:
   - опрос вообще идёт (время последнего цикла свежее)?
   - нет ли аккаунта в backoff (растёт до 15 мин при ошибках подряд)?
   - `records_fetched` в последнем цикле — не ноль ли (значит YClients не
     отдал запись, дело не в этом сервисе)?
3. **Событие есть, доставки нет.** В ленте у события «доставлено N/M» — если
   `N < M`, смотрите `push_deliveries` (`/v1/admin/events` отдаёт это же
   поле): статус `failed` — сетевая ошибка FCM, повторится на следующем
   событии/тике; статус `token_invalid` — устройство уже удалено с сервера,
   ему нужна новая регистрация (переустановка приложения или следующий
   keepalive-тик перерегистрирует активное устройство).
4. **Доставка `sent`, а на телефоне тихо.** Дело на самом телефоне:
   - разрешение на уведомления (`POST_NOTIFICATIONS`) выдано?
   - приложение не в списке «Принудительно остановлено» — тогда FCM вообще
     не долетает, поможет только открытие приложения (запустит догон);
   - экономия батареи для приложения отключена (иначе Doze откладывает
     доставку на часы даже при `android.priority=high`).
5. **Телефон зарегистрирован именно на этом сервисе, а не на старом.**
   Проверьте в блоке «Устройства» дашборда — есть искомый `label`/`device_id`?
   Нет — либо приложение ещё не обновлено до версии, использующей
   `neiro-push-events` (см. [push-setup.md](push-setup.md)), либо
   регистрация не прошла (401 в логе приложения = ключ не тот, см. §5 выше).

## 9. Расхождения с планом

Зафиксированы по факту (правило «расходится с реальностью — остановись и
спроси», [CLAUDE.md](../CLAUDE.md)) — полный разбор в
[progress.md](push-events/progress.md), здесь только список:

- Маршрут `/v2` — nginx на VPS + SSH-туннель, без Caddy на Pi (§7 выше).
- `/health` закрыт `ADMIN_API_KEY` вместо публичного ответа из раннего Этапа 1.
- `date` в событии — подстрокой из `datetime`, а не отдельным полем ответа
  YClients (там оно приходит с временем).
- `INVALID_ARGUMENT` от FCM не считается мёртвым токеном (расхождение со
  старым сервисом `neiro-push`, где считается).
- Сидирование `record_states` — свойство цикла **компании**, а не отдельного
  аккаунта.
- Курсор нового устройства — `max(events.id)` по всему журналу, не по
  аккаунту.
- Календарь в приложении правится данными из payload сразу, а не только при
  открытии приложения (план предполагал второе).
