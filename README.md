# Neiro

Android-приложение для педагогов: календарь занятий, профиль, архив, уведомления. Данные синхронизируются с [YClients](https://www.yclients.com/).

## Документация

| Тема | Документ | О чём |
|------|----------|--------|
| Push и FCM | [docs/push-setup.md](docs/push-setup.md) | Сервер на Pi, домен, Firebase, регистрация телефонов, тестовый push, `ADMIN_API_KEY`, где лежит БД |
| YClients API | [docs/yclients-integration.md](docs/yclients-integration.md) | Авторизация, сетевой слой, синхронизация, `local.properties` |
| Боковая панель | [docs/profile-drawer.md](docs/profile-drawer.md) | Drawer профиля, жесты, файлы в коде |
| Push-сервер (кратко) | [server/README.md](server/README.md) | API, деплой, скрипты — детали в [push-setup](docs/push-setup.md) |
| Дорожная карта | [TODO.md](TODO.md) | Что сделано и что в планах |

## Быстрый старт (разработка)

1. `local.properties` — SDK, YClients, push (см. [yclients-integration.md](docs/yclients-integration.md) и [push-setup.md](docs/push-setup.md)).
2. `app/google-services.json` — для FCM ([push-setup.md § Firebase](docs/push-setup.md#2-firebase-fcm)).
3. Сборка — Android Studio / Gradle на машине разработчика.

## Push-сервер

Публичный URL: `https://push.neiro.greemlab.ru`

| Действие | Команда / ссылка |
|----------|------------------|
| Деплой на Pi | `server/scripts/deploy.sh` — [подробнее](docs/push-setup.md#1-сервер-на-pi) |
| Health и устройства | `server/scripts/admin-status.sh` — [доступ](docs/push-setup.md#кто-может-заходить-на-health) |
| Тестовый push | `server/scripts/test-push.sh` — [подробнее](docs/push-setup.md#1-сервер-на-pi) |
| Где хранятся аккаунты и телефоны | [push-setup.md § хранение данных](docs/push-setup.md#где-лежат-аккаунты-и-устройства) |

Ключи на Pi:

```bash
ssh roster-b3 'grep ^API_KEY= ~/neiro-push/.env'        # в приложение (local.properties)
ssh roster-b3 'grep ^ADMIN_API_KEY= ~/neiro-push/.env'  # только админ: health, test-push
```

## Структура репозитория

```
app/                 Android-приложение (Kotlin, Compose)
app/.../push/        Регистрация FCM, приём push
server/              Push-сервер (FastAPI, Pi)
docs/                Документация
```

## Секреты (не в git)

| Файл | Содержимое |
|------|------------|
| `local.properties` | SDK, YClients, `NEIRO_PUSH_*`, подпись release |
| `app/google-services.json` | Firebase |
| `~/neiro-push/.env` на Pi | `API_KEY`, `ADMIN_API_KEY`, шифрование токенов |
| `~/neiro-push/secrets/` на Pi | FCM service account JSON |
