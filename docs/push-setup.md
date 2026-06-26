# Push-уведомления Neiro

Всё в этом репозитории: сервер (`server/`), приложение (`app/.../push/`), деплой на Pi.

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

```properties
NEIRO_PUSH_API_BASE_URL=https://push.neiro.greemlab.ru
NEIRO_PUSH_API_KEY=<API_KEY из ~/neiro-push/.env на Pi>
```

API_KEY на Pi:

```bash
ssh roster-b3 'grep ^API_KEY= ~/neiro-push/.env'
```

## 4. Как работает

```
YClients ←── опрос 15с/1ч ── Pi (neiro-push)
                                │
                           FCM push {action: sync}
                                │
                           Телефон(ы) → sync → уведомления
```

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
