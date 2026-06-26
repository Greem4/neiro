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

Проверка:

```bash
curl -s https://push.neiro.greemlab.ru/health
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
| `app/.../push/` | Регистрация устройства, FCM service |
