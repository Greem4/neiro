# Интеграция с YClients

Как приложение получает расписание и деньги. Прямых запросов к
`api.yclients.com` больше нет: телефон ходит в свой сервис на Pi, тот держит
ключи YClients и проксирует запросы — разбор в
[docs/neiro-push/](neiro-push/README.md).

## Из чего состоит

**Сетевой слой** (`data/network/`):

| Файл | Что делает |
|---|---|
| `NeiroApi.kt` | Сервис Neiro: вход, сессия, выход, обновление токена FCM |
| `YClientsApi.kt` | Данные YClients через прокси: записи, клиенты, сотрудники, зарплата |
| `YClientsClient.kt` | Один Retrofit на оба интерфейса; подставляет `Authorization` |
| `YClientsModels.kt` | Модели ответов YClients — те же, что и раньше: прокси отдаёт тело как есть |
| `TokenStorage.kt` | `device_token` и справочные поля аккаунта в `EncryptedSharedPreferences` |
| `YClientsRepository.kt` | Вход, разбор отказов, пагинация, зарплата |

**UI авторизации** (`ui/auth/`): `AuthScreen.kt`, `AuthViewModel.kt` — логин и
пароль, ничего больше. Формы ввода Partner Token нет: вводить нечего.

**Синхронизация** (`ui/sync/`): `SyncViewModel.kt` — перенос записей в
локальный календарь.

## Настройка

Ключи YClients в сборку не попадают. В `local.properties` нужен только адрес
сервиса и ключ приложения к нему:

```properties
NEIRO_PUSH_API_BASE_URL=https://push.neiro.greemlab.ru
NEIRO_PUSH_API_KEY=<API_KEY из ~/neiro-push/.env>
```

Адрес — без номера версии: пути в Retrofit начинаются с `v1/`.

`YCLIENTS_PARTNER_TOKEN` и `YCLIENTS_COMPANY_ID` живут в `.env` на Pi
([DEPLOY.md](neiro-push/DEPLOY.md#переменные-окружения)) и на телефон не
попадают ни в каком виде.

## Вход

1. Боковая панель профиля → **«YClients»**
2. Логин и пароль от `yclients.com`
3. **«Синхронизировать записи»**

Сервер логинится в YClients партнёрским токеном, находит `staff_id` по имени
пользователя, регистрирует устройство для пушей и возвращает `device_token` —
всё одним запросом. Имя в профиле YClients должно совпадать с карточкой
сотрудника в филиале, иначе вход отклоняется с `409` и объяснением.

## Что происходит при отказах

| Код от сервиса | Значение | Приложение |
|---|---|---|
| `401` | `device_token` отозван или неизвестен | Полный выход, экран входа |
| `409` | `user_token` на сервере протух | Баннер «Нужен повторный вход», данные и пуши остаются |
| `429` | Слишком часто | Пауза до `Retry-After` |
| `502`, `504` | YClients не ответил | «YClients недоступен, попробуйте позже» |
| нет ответа | Pi недоступен | «Нет связи с сервером Neiro · показаны сохранённые данные», повтор с backoff |

## Используемые методы

Все — на `https://push.neiro.greemlab.ru`, авторизация `Bearer <device_token>`
(кроме входа, он идёт по ключу приложения):

- `POST /v1/auth/login`, `POST /v1/auth/logout`, `GET /v1/session`
- `POST /v1/devices/fcm` — обновление токена Firebase
- `GET /v1/events`, `POST /v1/events/ack` — догон пропущенных событий
- `GET /v1/yclients/records`, `/clients`, `/staff`, `/salary/daily`,
  `/salary/calculations`, `/salary/calculations/{id}`

`company_id` и `staff_id` подставляет сервер — в запросах их нет. Полная
спецификация — [API.md](neiro-push/API.md).

## Лимиты

| Что | Лимит |
|---|---|
| Вход | 5 попыток / 15 мин на устройство и на IP |
| Прокси | 60 запросов / мин на устройство |
| YClients (общий, расходуется сервером) | 200 запросов/мин, 5 запросов/с на IP |

Документация YClients: https://developer.yclients.com/ru/
