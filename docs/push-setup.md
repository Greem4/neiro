# Push-уведомления Neiro

Всё в этом репозитории: сервер (`server/`), приложение (`app/.../push/`), деплой на Pi.

## 1. Сервер на Pi

```bash
chmod +x server/scripts/*.sh
./server/scripts/deploy.sh
```

Скрипт:
- копирует `~/neiro-push` на Pi;
- патчит **Caddy на Pi** (маршрут `/neiro-push`, без правок Roster);
- опрос YClients: **15 с** днём (09:00–21:00 МСК), **1 ч** ночью (21:00–09:00);
- публичный URL: `https://medicine.greemlab.ru/neiro-push` (через существующий VPS-туннель → Caddy :80).

Проверка:

```bash
curl -s https://medicine.greemlab.ru/neiro-push/health
```

Автозапуск после перезагрузки Pi (опционально):

```bash
./server/scripts/install-autostart.sh
```

## 2. Firebase (FCM)

1. [Firebase Console](https://console.firebase.google.com/) → Android-приложение `ru.greemlab.neiro`.
2. Скачать `google-services.json` → `app/google-services.json`.
3. Service Account JSON → `server/secrets/fcm-service-account.json` на Pi.
4. В `~/neiro-push/.env` на Pi: `FCM_PROJECT_ID=<из json>`.

## 3. local.properties (Mac)

```properties
NEIRO_PUSH_API_BASE_URL=https://medicine.greemlab.ru/neiro-push
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
| `server/scripts/patch-pi-caddy.py` | Маршрут в Caddy на Pi |
| `app/.../push/` | Регистрация устройства, FCM service |
