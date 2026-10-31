# Инфраструктура нового `neiro-push`

Где сервис живёт, какие порты и маршруты занимает и как встаёт рядом с
работающим `neiro-push-events`, ничего не задевая. Порядок запуска во времени —
[ROLLOUT.md](ROLLOUT.md).

## Где живёт сейчас: VPS, а не Pi

**С 19.10.2026 сервис работает на VPS** `roster-vps` (176.12.65.86, вход под
`root`), а не на Raspberry Pi. Причина — Pi выключался, и вместе с ним
вставали пуши и весь подключённый режим (`502` на публичном адресе).

| Что | Где |
|---|---|
| Код, `.env`, `secrets/` | `roster-vps:~/neiro-push` |
| Контейнер | `neiro-push`, `127.0.0.1:8012` на VPS |
| База | том `neiro-push_neiro_push_data` на VPS |
| Дашборд | `https://push.neiro.greemlab.ru/dashboard` — адрес не поменялся |

Ключи, база и ключ FCM перенесены с Pi как есть: телефонам перелогиниваться
не нужно, `API_KEY` в APK прежний. Снимок базы на момент переезда —
`neiro-push/backups/neiro_push-20261019-migration.db` на Mac.

**Что осталось на Pi — для отката.** Контейнер остановлен и переведён в
`--restart=no` (после перезагрузки Pi сам не поднимется), туннель
`neiro-push-tunnel.service` выключен и убран из автозапуска. Каталог
`~/neiro-push` и том с базой на Pi не тронуты, но база там застыла на
19.10.2026. Откат:

```bash
ssh roster-vps 'F=/etc/nginx/sites-available/push.neiro.greemlab.ru; \
  cp $F.bak-20261019-pre-vps $F && nginx -t && systemctl reload nginx; \
  cd ~/neiro-push && docker compose stop'
ssh roster-b3 'docker update --restart=unless-stopped neiro-push && docker start neiro-push \
  && systemctl --user enable --now neiro-push-tunnel.service'
```

Перед откатом базу с VPS перенести обратно на Pi, иначе пропадёт всё, что
накопилось после переезда. Два сервиса одновременно не запускать: оба
опрашивают YClients и шлют одинаковые пуши.

**Скрипты ещё не переведены.** `scripts/_ssh.sh` по умолчанию выбирает Pi
(`roster-b3` / `roster-pi-remote`), а `deploy.sh` поднимает туннель на Pi.
Запуск без переменной зальёт код на Pi и **оживит старый сервис**. Пока
скрипты не поправлены — любой скрипт только с хостом VPS:

```bash
PI_SSH=roster-vps ./neiro-push/scripts/deploy.sh
PI_SSH=roster-vps ./neiro-push/scripts/logs.sh
PI_SSH=roster-vps ./neiro-push/scripts/backup.sh
```

`deploy.sh` на VPS напишет, что не смог поднять туннель, — это ожидаемо и
деплою не мешает: туннеля в маршруте больше нет. `install-tunnel.sh` больше
не нужен.

Разделы ниже про поколения, порты и уборку — история перехода на Pi; где они
говорят «на Pi» про работающий сервис, теперь читать «на VPS».

## Три поколения сервиса

Имя `neiro-push` уже носил первый сервис. Он погашен 02.08.2026, но его следы
на Pi остались, и новый сервис занимает то же имя — значит следы нужно убрать
осознанно, а не наткнуться на них при первом деплое.

| Поколение | Каталог в репозитории | На Pi | Порт | Публичный путь | Состояние |
|---|---|---|---|---|---|
| 1. `neiro-push` (старый) | `server/` | `~/neiro-push` | 8010 | `/` (корень) | Погашен, код и данные лежат |
| 2. `neiro-push-events` | `neiro-push-events/` | `~/neiro-push-events` | 8011 | `/v2` | Работает, обслуживает установленное приложение |
| 3. `neiro-push` (новый) | `neiro-push/` | `~/neiro-push` | **8012** | **`/v1`** | Проектируется |

Второе поколение живёт до конца перехода и гасится последним. Первое —
освобождает имя перед тем, как третье займёт его каталог.

## Занятые ресурсы и что берёт новый сервис

| Ресурс | Занято | Новый берёт | Почему так |
|---|---|---|---|
| Порт на Pi | 8010 (старый, свободен фактически), 8011 (events) | `8012` | 8010 формально свободен, но занят конфигами старого поколения. Отдельный порт позволяет поднять новый сервис, пока старый ещё не разобран |
| Имя контейнера | `neiro-push` (остановлен), `neiro-push-events` | `neiro-push` | Имя освобождается на шаге уборки: `docker rm neiro-push` |
| Docker volume | `neiro_push_data`, `neiro_push_events_data` | `neiro_push_data` | После архивации старой БД том удаляется и создаётся заново пустым |
| Каталог на Pi | `~/neiro-push`, `~/neiro-push-events` | `~/neiro-push` | Старый переезжает в `~/neiro-push-legacy-2026-08` |
| Туннельный порт на VPS | 18082 (events) | `18083` | |
| systemd-юнит на Pi | `neiro-push-events-tunnel.service` | `neiro-push-tunnel.service` | Проверить, что одноимённого юнита от первого поколения нет |
| Публичный путь | `/` (первое поколение), `/v2` (events) | `/v1` | |

## Публичный маршрут

```
https://push.neiro.greemlab.ru/v1/*
        │
        ▼  DNS → 176.12.65.86 (VPS)
   ┌──────────────────────────────────────┐
   │ VPS: nginx, TLS от Certbot           │
   │   location /v1/ {                    │
   │     proxy_pass http://127.0.0.1:8012;  ← БЕЗ слеша в конце
   │   }                                  │
   └──────────────────────────────────────┘
        │  путь уходит целиком: /v1/auth/login
        ▼
   ┌──────────────────────────────────────┐
   │ VPS: 127.0.0.1:8012 → контейнер      │
   │   FastAPI отвечает на /v1/auth/login │
   └──────────────────────────────────────┘
```

До 19.10.2026 между nginx и контейнером был reverse SSH-туннель
`neiro-push-tunnel.service` (VPS:18083 → Pi:8012). Теперь nginx ходит в
контейнер напрямую; бэкап конфига со старым портом —
`/etc/nginx/sites-available/push.neiro.greemlab.ru.bak-20261019-pre-vps`.

**Отсутствие слеша в `proxy_pass` — главное отличие от `/v2`.** У сервиса
второго поколения `proxy_pass … :18082/` срезает префикс, поэтому внутри
контейнера пути начинаются с `/v1`, а публично получается `/v2/v1/...`. Здесь
префикс не срезается: что написано в приложении, то и обрабатывает FastAPI.
Один путь, один смысл, никаких «почему тут два номера версии».

Отсюда конфигурация клиента:

```properties
NEIRO_PUSH_API_BASE_URL=https://push.neiro.greemlab.ru
```

и пути в Retrofit — `v1/auth/login`, `v1/events`, `v1/yclients/records`.

Caddy на Pi в маршруте не участвует — как и у второго поколения. У него есть
спящий блок через cloudflared, но DNS смотрит на VPS
([deploy.md](../deploy.md#про-caddy-почему-его-тут-нет)).

## Структура сервиса

```
neiro-push/
  app/
    main.py        — приложение FastAPI, монтирование роутеров
    config.py      — Settings: ключи, партнёрский токен, интервалы
    database.py    — SQLite: accounts, devices, record_states, events, deliveries, poll_runs
    security.py    — SecretBox (Fernet), хэш device_token, сравнение ключей
    auth.py        — вход, выпуск и проверка device_token          ← новое
    proxy.py       — семь прокси-эндпоинтов YClients                ← новое
    ratelimit.py   — лимиты входа и запросов                        ← новое
    device_events.py — лента событий телефону и ack курсора         ← новое
    yclients.py    — клиент YClients: login, staff, records, clients, salary
    poller.py      — опрос YClients, генерация событий
    events.py      — сравнение снимков, типы событий
    fcm.py         — отправка push
    dashboard.py   — данные для дашборда
  templates/       — дашборд
  scripts/         — deploy.sh, dev.sh, logs.sh, backup.sh, restore.sh,
                     install-tunnel.sh, patch-vps-nginx-v1.sh, _ssh.sh
  tests/
  docker-compose.yml, docker-compose.dev.yml, Dockerfile, requirements.txt
```

Что берётся из `neiro-push-events` практически как есть: `poller.py`,
`events.py`, `fcm.py`, `dashboard.py`, `templates/`, скрипты. Это работающий,
обкатанный код — переписывать его «раз уж с чистого листа» значит потерять
полгода отладки на границах YClients (даты, часовые пояса, `changed_after`,
пагинация). Чистый лист здесь — про схему доступа, а не про то, что уже
проверено боем.

Что меняется по существу: `partner_token` берётся из настроек, а не из
аккаунта; появляются `auth.py`, `proxy.py`, `ratelimit.py`, `device_events.py`;
в `database.py` —
поля `token_hash`, `revoked_at`, `reauth_required`, `user_login`, `last_auth_at`;
у поллера — пропуск аккаунтов с `reauth_required`.

## Переменные окружения

`~/neiro-push/.env` на VPS (`roster-vps`), права `600`, генерируется деплоем при первом
запуске:

```bash
API_KEY=…                  # ключ приложения: только POST /v1/auth/login
ADMIN_API_KEY=…            # ПАРОЛЬ ОТ ДАШБОРДА, плюс /health и /v1/admin/*
RELEASE_NOTIFY_KEY=…       # GitHub Actions: только POST /v1/release/notify
TOKEN_ENCRYPTION_KEY=…     # Fernet: шифрование user_token в БД
YCLIENTS_PARTNER_TOKEN=…   # единственное место, где он теперь живёт
YCLIENTS_COMPANY_ID=…      # филиал по умолчанию при входе
POLL_INTERVAL_SECONDS=15     # боевое значение; при первом деплое скрипт пишет 10
POLL_NIGHT_INTERVAL_SECONDS=3600
QUIET_START_HOUR=23
FCM_CREDENTIALS_PATH=/secrets/fcm-service-account.json
FCM_PROJECT_ID=…
DATABASE_PATH=/data/neiro_push.db
LOG_LEVEL=info
```

### Как зайти в дашборд

`https://push.neiro.greemlab.ru/` (голый адрес сам ведёт на `/dashboard`).
Форма спрашивает «ключ» — это **`ADMIN_API_KEY` целиком**, никакого отдельного
пароля у дашборда нет. Посмотреть рабочее значение:

```bash
ssh roster-vps "grep ^ADMIN_API_KEY= ~/neiro-push/.env"
```

Вход ставит куку на 30 дней, так что вводить ключ каждый раз не придётся.
Сменить его можно правкой `.env` и `docker compose up -d` — приложение этот
ключ не знает и не заметит.

### Посмотреть ключи на сервере

Перенесено из README: карточке проекта команды эксплуатации не место, а нужны
они регулярно — при заполнении `local.properties` и при входе в дашборд.

```bash
ssh roster-vps 'grep ^API_KEY= ~/neiro-push/.env'        # в приложение (local.properties)
ssh roster-vps 'grep ^ADMIN_API_KEY= ~/neiro-push/.env'  # только админ: health, test-push
```

### Пуш о новом релизе

`POST /v1/release/notify` (см. [API.md](API.md#post-v1releasenotify)) закрыт
своим ключом, и пока его нет в `.env`, эндпоинт отвечает `503` — релиз просто
выходит молча, как раньше.

Включается в двух местах, значение одно:

```bash
KEY=$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')

# 1. VPS: строка в .env и перезапуск
ssh roster-vps "printf 'RELEASE_NOTIFY_KEY=%s\n' '$KEY' >> ~/neiro-push/.env \
  && cd ~/neiro-push && docker compose up -d"

# 2. GitHub: секрет, которым release.yml дёргает сервер
gh secret set NEIRO_PUSH_RELEASE_NOTIFY_KEY --body "$KEY"
```

Проверка без выпуска релиза — тем же запросом, что делает workflow. Версия
должна быть не новее установленной на телефонах, иначе придёт настоящее
уведомление:

```bash
curl -sS -X POST https://push.neiro.greemlab.ru/v1/release/notify \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"version_name":"0.0.1"}'
# {"version_name":"0.0.1","devices":1,"sent":1,"failed":0}
```

`devices` — сколько телефонов с живым токеном FCM знает сервер, `sent` —
скольким пуш ушёл. `sent: 0` при `devices > 0` значит мёртвые токены: они
гасятся, телефон пришлёт новый сам через `POST /v1/devices/fcm`.

### Карта секретов

Где что лежит и что делать, если потерялось. Правило простое: **боевое значение
одно и живёт на VPS**, остальное — копии и записные книжки.

| Секрет | Хранится | Копии | Если потерян |
|---|---|---|---|
| `YCLIENTS_PARTNER_TOKEN` | `~/neiro-push/.env` на VPS | закомментирован в `local.properties`, снимки `env-*.txt` | взять новый в `developer.yclients.com`, положить в `.env`, перезапустить |
| `API_KEY` (ключ приложения) | `~/neiro-push/.env` | `neiro-push/.env`, `local.properties` → `NEIRO_PUSH_API_KEY` | сгенерировать заново — но APK придётся пересобрать и раскатать |
| `ADMIN_API_KEY` (дашборд) | `~/neiro-push/.env` | `neiro-push/.env` | сгенерировать заново; приложение его не знает и не заметит |
| `RELEASE_NOTIFY_KEY` | `~/neiro-push/.env` | GitHub Secrets → `NEIRO_PUSH_RELEASE_NOTIFY_KEY` | сгенерировать заново в обоих местах; до этого релиз выйдет молча |
| `TOKEN_ENCRYPTION_KEY` | `~/neiro-push/.env` | снимки `env-*.txt` в `neiro-push/backups/` | **ничем**: `user_token_enc` не расшифровать, всем нужен повторный вход |
| `user_token` YClients | БД сервера, шифрован Fernet | снимки БД | повторный вход логином и паролем |
| `device_token` | только телефон; в БД лишь `sha256` | — | повторный вход |
| Ключ FCM | `~/neiro-push/secrets/fcm-service-account.json` | тот же файл у `neiro-push-events` | новый service account в консоли Firebase |
| Подписной ключ APK | `Greemlab.jks` на Mac, пароли в `local.properties` | — | **ничем**: установленное приложение больше не обновить |
| Секреты релизного workflow | GitHub Actions | — | заводятся владельцем; на 09.08.2026 их ноль |

Отдельно про третье поколение: каталог `~/neiro-push` достался ему от первого,
вместе с `.env` от 26.06.2026. `deploy.sh` создаёт файл только если его нет
(`if [[ ! -f .env ]]`), поэтому новых ключей не сгенерировалось — сервис живёт
на `API_KEY` и `ADMIN_API_KEY` первого поколения. Публичных сборок с ними не
выпускалось, но `API_KEY` — единственный секрет, который едет в APK, и для
чистого старта его стоит сменить **до первой сборки**: потом это стоит
пересборки и раскатки. Решение за владельцем, само по себе ничего не ломается.

Локальные копии ключей проверяются **входом, а не глазами**. 09.08.2026 в
`neiro-push-events/.env` нашёлся `ADMIN_API_KEY`, обрезанный на последний
символ: 42 знака вместо 43, при копировании потерялась хвостовая `g`. На вид
он неотличим от целого, а дашборд молча отвечал `401` — и выглядело это как
«сервис не пускает». Сверено с Pi и исправлено.

Ключ FCM (`secrets/fcm-service-account.json`) — тот же service account, что у
второго поколения; деплой копирует его из `~/neiro-push-events/secrets/`, если
своего ещё нет. Проект Firebase один, устройства различаются по FCM-токену,
конфликта между сервисами нет.

`YCLIENTS_PARTNER_TOKEN` при первом запуске **не генерируется** — его кладут
руками. Деплой обязан падать с внятным сообщением, если переменная пуста:
сервис без партнёрского токена не сможет ни залогинить пользователя, ни
опросить YClients.

## Скрипты

Клонируются из `neiro-push-events/scripts` с заменой имён, портов и юнита:

| Скрипт | Что делает | Что меняется относительно events |
|---|---|---|
| `deploy.sh` | rsync на сервер (`PI_SSH=roster-vps`), `.env` при первом запуске, `docker compose up -d --build`, проверка туннеля (на VPS не нужна, предупреждение безвредно), ожидание `/health`, проверка публичного адреса | Пути `~/neiro-push`, порт 8012, юнит `neiro-push-tunnel.service`, публичный URL `…/v1`, проверка непустого `YCLIENTS_PARTNER_TOKEN` |
| `install-tunnel.sh` | systemd --user юнит Pi → VPS | Порт 18083, юнит `neiro-push-tunnel.service`, локальная цель 8012 |
| `patch-vps-nginx-v1.sh` | Добавляет `location /v1/`, `/dashboard`, `/health` в сайт nginx на VPS и переводит корень на редирект к дашборду, идемпотентно | **`proxy_pass` без завершающего слеша** |
| `dev.sh`, `logs.sh`, `backup.sh`, `restore.sh` | Локальный запуск, логи, резервные копии | Имена контейнера и БД |

Отдельно нужен `scripts/revoke-device.sh` — отзыв `device_token` из командной
строки: кнопка в дашборде появится, но в момент, когда она понадобится,
удобнее одна команда.

## Обновление развёрнутого сервиса

Тот же `deploy.sh`: он делает rsync кода, пересобирает образ и поднимает
контейнер. `.env` при этом не трогается — ключи и токен на сервере остаются как
были, поэтому обновление безопасно повторять.

```bash
PI_SSH=roster-vps ./neiro-push/scripts/deploy.sh
PI_SSH=roster-vps ./neiro-push/scripts/logs.sh --tail 50      # ошибок старта нет
```

Данные живут в томе `neiro_push_data` и обновление переживают. Перед заметными
изменениями — `PI_SSH=roster-vps ./neiro-push/scripts/backup.sh`.

## Проверка после развёртывания

```bash
BASE=https://push.neiro.greemlab.ru
ADMIN=$(ssh roster-vps 'grep ^ADMIN_API_KEY= ~/neiro-push/.env | cut -d= -f2-')

curl -fsS -H "Authorization: Bearer $ADMIN" "$BASE/health"          # 200
curl -s -o /dev/null -w '%{http_code}\n' "$BASE/v1/auth/login"      # 401: путь жив, ключа нет
curl -s -o /dev/null -w '%{http_code}\n' "$BASE/v1/events"          # 401, а не 404
ssh roster-vps 'docker ps --format "{{.Names}}\t{{.Ports}}" | grep neiro'
```

`404` на `/v1/events` означает, что на сервере версия до этапа 5а — нужен
передеплой, иначе новое приложение останется без догона пропущенных событий.

Последняя команда должна показать один контейнер `neiro-push` на 8012, который
не перезапускается по кругу. `neiro-push-events` погашен, на VPS его никогда
не было.

## Уборка первого поколения

Делается **до** первого деплоя нового сервиса — иначе rsync ляжет поверх чужих
файлов в `~/neiro-push`.

```bash
# 1. Забрать данные и ключи старого сервиса на Mac
./server/scripts/backup.sh                       # если скрипт ещё работает
ssh roster-b3 'tar czf ~/neiro-push-legacy.tgz -C ~ neiro-push'
scp roster-b3:~/neiro-push-legacy.tgz ~/backups/

# 2. Освободить имена
ssh roster-b3 'docker rm -f neiro-push 2>/dev/null; \
               mv ~/neiro-push ~/neiro-push-legacy-2026-08'

# 3. Том со старой БД — только после того, как архив лежит на Mac
ssh roster-b3 'docker volume rm neiro_push_data'
```

Из репозитория каталог `server/` удаляется на шаге уборки в
[ROLLOUT.md](ROLLOUT.md#шаг-6--уборка): код остаётся в истории git, вернуть его
можно в любой момент, а два мёртвых сервиса рядом с живым только путают.
