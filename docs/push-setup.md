# Push-уведомления Neiro (старый сервис `neiro-push`)

> **Архив.** Имя `neiro-push` с тех пор занято третьим поколением сервиса
> (маршрут `/v1`, порт 8012) — актуальная документация в
> [docs/neiro-push/](neiro-push/README.md). Здесь описано первое поколение
> (порт 8010), оно погашено 02.08.2026, а код удалён из репозитория —
> он в истории git, коммит
> [`5a95262`](https://github.com/Greem4/neiro/tree/5a952625933583c34d567fa037dddd63dbd71d46/server).
> По-прежнему верна только часть про Firebase.

Сервер (`server/`) лежал в этом же репозитории; приложение — `app/.../push/`.

**Этот документ — про старый сервис `neiro-push` (порт 8010).** Он обслуживает
только сборку приложения **0.6.9.0 и старше** и живёт локальным опросом
YClients с телефона по таймеру. С версии **0.7.0.0** приложение полностью
переходит на новый сервис `neiro-push-events` (порт 8011, маршрут `/v2`),
который сам опрашивает YClients и шлёт события push'ем — без опроса с
телефона. Новый сервис, его эксплуатация и дашборд — в
[docs/push-events.md](push-events.md); история разработки и контракт — в
[docs/push-events/](push-events/).

Переход делается устройство за устройством (обратной совместимости между
сервисами нет — новая сборка со старым сервисом не работает вообще, см.
[app.md §4.3](push-events/app.md#43-pushneirofirebasemessagingservicekt)).
Пока не все телефоны обновлены, `neiro-push` держим живым — гасить его
раньше срока нельзя, читайте [Этап 10 плана](push-events/plan.md) перед тем
как его останавливать.

## 1. Сервер на Pi

```bash
chmod +x server/scripts/*.sh
./server/scripts/deploy.sh
```

Скрипт:
- копирует `~/neiro-push` на Pi;
- патчит **Caddy на Pi** (vhost `push.neiro.greemlab.ru`);
- опрос YClients: **15 с** днём (09:00–21:00 МСК), **1 ч** ночью (21:00–09:00);
- публичный URL: `https://push.neiro.greemlab.ru` (DNS → VPS → SSH-туннель → Caddy на Pi).

Проверка (нужен `ADMIN_API_KEY` — не в приложении, только у тебя):

```bash
./server/scripts/admin-status.sh health
./server/scripts/admin-status.sh overview
```

Тестовый push на все телефоны:

```bash
./server/scripts/test-push.sh
```

На одно устройство:

```bash
./server/scripts/test-push.sh --device-id 'neiro-Pixel-...'
```

Автозапуск после перезагрузки Pi (опционально):

```bash
./server/scripts/install-autostart.sh
```

## 2. Firebase (FCM)

1. [Firebase Console](https://console.firebase.google.com/) → три Android-приложения:
   - `ru.greemlab.neiro` (release)
   - `ru.greemlab.neiro.debug` (debug-сборка)
   - `ru.greemlab.neiro.prerelease` (pre-release)
2. Скачать `google-services.json` (все три client в одном файле) → `app/google-services.json`.
   Шаблон: `app/google-services.json.example`.
3. Service Account JSON → `server/secrets/fcm-service-account.json` на Pi.
4. В `~/neiro-push/.env` на Pi: `FCM_PROJECT_ID=<из json>`.

## 3. local.properties (Mac)

**Актуально только для сборки ≤0.6.9.0.** С версии 0.7.0.0 `local.properties`
указывает на новый сервис — `NEIRO_PUSH_API_BASE_URL=https://push.neiro.greemlab.ru/v2`
и `API_KEY` из `~/neiro-push-events/.env`, см. [push-events.md §5](push-events.md#5-переменные-окружения).

Для старой сборки было так:

```properties
NEIRO_PUSH_API_BASE_URL=https://push.neiro.greemlab.ru
NEIRO_PUSH_API_KEY=<API_KEY из ~/neiro-push/.env на Pi>
```

API_KEY старого сервиса на Pi:

```bash
ssh roster-b3 'grep ^API_KEY= ~/neiro-push/.env'
```

## 4. Как работает (старый сервис)

```
YClients ←── опрос 15с/1ч ── Pi (neiro-push)
                                │
                           FCM push {action: sync}
                                │
                           Телефон(ы) → sync → уведомления
```

Новый сервис работает иначе — сам шлёт события в payload, без похода в
YClients с телефона, см. [push-events.md §1](push-events.md#1-как-устроено).

- Несколько телефонов одного педагога: один опрос, push на все `fcm_token`.
- При активном server push телефон **не** опрашивает YClients в фоне — только по FCM и при открытии приложения.

## 5. Файлы

| Путь | Назначение |
|------|------------|
| `server/app/` | FastAPI, опрос, FCM |
| `server/scripts/deploy.sh` | Деплой на Pi |
| `server/scripts/patch-pi-caddy.py` | vhost в Caddy на Pi |
| `server/scripts/patch-vps-nginx.sh` | nginx + certbot на VPS |
| `server/scripts/test-push.sh` | Тестовый FCM push |
| `server/scripts/admin-status.sh` | Health и список устройств |
| `app/.../push/` | Регистрация устройства, FCM service |

## 6. Доступ и хранение данных

### Кто может заходить на `/health`

Публично без ключа — **нет**. Нужен заголовок:

```http
Authorization: Bearer <ADMIN_API_KEY>
```

- `API_KEY` — в APK (`local.properties`), только регистрация устройств.
- `ADMIN_API_KEY` — **только на Pi и у тебя на Mac**, не в приложении.

SSH-ключи к Pi/VPS — отдельная защита shell-доступа; для HTTP используем admin-токен, не SSH pubkey.

`ADMIN_API_KEY` на Pi:

```bash
ssh roster-b3 'grep ^ADMIN_API_KEY= ~/neiro-push/.env'
```

### Где лежат аккаунты и устройства

SQLite на Raspberry Pi, Docker-том `neiro_push_data`:

| Что | Где |
|-----|-----|
| Файл БД | `~/neiro-push` → контейнер `/data/neiro_push.db` |
| Таблица `accounts` | `company_id`, `staff_id`, зашифрованные YClients-токены |
| Таблица `devices` | `device_id`, `fcm_token`, модель телефона, `last_seen_at` |
| FCM credentials | `~/neiro-push/secrets/fcm-service-account.json` |
| Секреты | `~/neiro-push/.env` (`API_KEY`, `ADMIN_API_KEY`, `TOKEN_ENCRYPTION_KEY`) |

Посмотреть устройства без API:

```bash
ssh roster-b3 'docker exec neiro-push python3 -c "
import sqlite3
c=sqlite3.connect(\"/data/neiro_push.db\")
for row in c.execute(\"SELECT device_id, label, last_seen_at FROM devices\"):
    print(row)
"'
```
