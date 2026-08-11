# Спецификация API

Эндпоинты нового сервиса `neiro-push`. Контекст и обоснование —
[ARCHITECTURE.md](ARCHITECTURE.md), инфраструктура — [DEPLOY.md](DEPLOY.md).

## Про префиксы

Публичный адрес — `https://push.neiro.greemlab.ru/v1/…`, и ровно этот путь
видит FastAPI внутри контейнера: nginx на VPS проксирует `location /v1/` **без
завершающего слеша** в `proxy_pass`, то есть префикс не срезается.

Это сделано специально, в отличие от `neiro-push-events`: там `/v2` срезался, и
публичный URL получался `/v2/v1/…` — два номера версии подряд, каждый раз
требующие объяснения. Здесь один путь и один смысл.

В приложении:

```properties
NEIRO_PUSH_API_BASE_URL=https://push.neiro.greemlab.ru
```

а пути в Retrofit начинаются с `v1/`.

## Аутентификация

Три разных ключа, три разные роли:

| Ключ | Заголовок | Что открывает |
|---|---|---|
| `API_KEY` (он же ключ приложения, в APK) | `Authorization: Bearer <API_KEY>` | Только `POST /v1/auth/login` |
| `device_token` (выдаётся при входе) | `Authorization: Bearer <device_token>` | Всё остальное, в пределах одного аккаунта |
| `ADMIN_API_KEY` | `Authorization: Bearer <ADMIN_API_KEY>` | `/health`, `/v1/admin/*`, дашборд |

`device_token` — 32 случайных байта в base64url. На сервере хранится только
`sha256`; восстановить его из БД нельзя, потерялся на телефоне — нужен новый
вход.

---

## Вход и сессия

### `POST /v1/auth/login`

Единственный эндпоинт, куда попадает пароль YClients. Заголовок —
`Bearer <API_KEY>`.

```json
{
  "login": "+79991234567",
  "password": "…",
  "device_id": "e1c8…",
  "fcm_token": "…",
  "label": "Pixel 7",
  "app_version": "0.2.0"
}
```

`200 OK`:

```json
{
  "device_token": "9Yh2…",
  "account": {
    "company_id": 520135,
    "staff_id": 2008329,
    "user_name": "Светлана",
    "avatar_url": "https://…"
  },
  "last_event_id": 4821
}
```

| Код | Когда | Что делает приложение |
|---|---|---|
| `401 invalid_credentials` | YClients не принял логин или пароль | «Неверный логин или пароль» |
| `409 staff_not_found` | Имя пользователя не совпало ни с одной карточкой в `book_staff` | Показать текст сервера: имя в профиле YClients должно совпадать с карточкой сотрудника |
| `429 too_many_attempts` | Больше 5 попыток за 15 минут с этого `device_id` или IP | Пауза до `Retry-After` |
| `502 upstream_error` | YClients недоступен | «YClients недоступен, попробуйте позже» |

`fcm_token` необязателен: Firebase выдаёт его не всегда (нет Google-сервисов,
сбой, сборка без `google-services.json`), а расписание и деньги нужны и без
пушей. Устройство без токена поллер при рассылке пропускает — иначе отправка
вернула бы `token_invalid` и снесла бы вместе с ним рабочий `device_token`.
Токен доедет позже через `POST /v1/devices/fcm`.

Вход повторно с того же `device_id` — нормальная операция: старый
`device_token` этого устройства отзывается, выдаётся новый, курсор событий
(`last_ack_event_id`) сохраняется. Событий за время без связи телефон не
теряет.

### `POST /v1/auth/logout`

`Bearer <device_token>` → `204`. Устройство удаляется, токен отзывается. Если
это было последнее устройство аккаунта — аккаунт остаётся (с ним остаётся
`user_token`), но поллер его пропускает: слать пуши некому.

### `GET /v1/session`

`Bearer <device_token>` → состояние без похода в YClients. Приложение
спрашивает при запуске.

```json
{
  "account": { "company_id": 520135, "staff_id": 2008329, "user_name": "Светлана", "avatar_url": "https://…" },
  "reauth_required": false,
  "last_event_id": 4821
}
```

`reauth_required: true` — `user_token` протух, нужен повторный вход паролем.

### `POST /v1/devices/fcm`

`Bearer <device_token>`, тело `{"fcm_token": "…"}` → `204`. Обновление токена
FCM, когда Firebase его перевыпустил. Заменяет часть нынешней перерегистрации
устройства.

---

## События

Те же данные, что и сегодня, но без `device_id` в пути — устройство
определяется по токену.

### `GET /v1/events?since=<id>&limit=<n>`

`Bearer <device_token>`. `limit` — 1…500, по умолчанию 100.

```json
{
  "events": [
    {
      "id": 4822,
      "staff_id": 2008329,
      "type": "RESCHEDULED",
      "client_name": "Иванов Пётр",
      "date": "2026-08-12",
      "time": "17:00",
      "kind": "LESSON",
      "prev_date": "2026-08-11",
      "prev_time": "18:00"
    }
  ],
  "last_event_id": 4822,
  "has_more": false
}
```

Формат `EventPayload` не меняется — разбор в приложении остаётся прежним.

### `POST /v1/events/ack`

`Bearer <device_token>`, тело `{"last_event_id": 4822}` → `204`.

---

## Прокси YClients

Общее для всех:

- заголовок `Authorization: Bearer <device_token>`;
- `company_id` и `staff_id` подставляет сервер, в запросе их нет;
- **тело ответа YClients возвращается без изменений**, вместе с его кодом —
  включая `success`, `data`, `meta`;
- ошибки уровня прокси описаны ниже и отличаются от ошибок YClients телом
  `{"detail": "…"}` без поля `success` (формат FastAPI; приложение различает их
  по коду ответа, а не по телу).

### `GET /v1/yclients/records`

Параметры: `start_date`, `end_date` (обязательные, `YYYY-MM-DD`), `page`,
`count`, `changed_after`, `with_deleted`. Соответствует
`/records/{company_id}` с `staff_id` аккаунта.

### `GET /v1/yclients/clients`

Параметры: `page`, `count`.

### `GET /v1/yclients/staff`

Без параметров. `/book_staff/{company_id}` — нужен приложению для карточек
сотрудников; определение своего `staff_id` через него больше не делается.

### `GET /v1/yclients/salary/daily`

Параметры: `date_from`, `date_to`. Диапазон в будущее YClients отбивает `422` —
код проходит наружу как есть.

### `GET /v1/yclients/salary/calculations`

Параметры: `date_from`, `date_to`. Период больше года — `422` от YClients.

### `GET /v1/yclients/salary/calculations/{calculation_id}`

Детализация начисления: позиции со ставками.

### Ошибки прокси

| Код | Тело | Значение |
|---|---|---|
| `401` | `{"detail": "invalid_device_token"}` | Токен неизвестен, отозван или устройство удалено → полный выход |
| `409` | `{"detail": "reauth_required"}` | `user_token` протух → повторный вход паролем, аккаунт и пуши живы |
| `429` | `{"detail": "rate_limited"}` + `Retry-After` | Больше 60 запросов в минуту с устройства |
| `502` | `{"detail": "upstream_error"}` | YClients не ответил или ответил 5xx |
| `504` | `{"detail": "upstream_timeout"}` | YClients не уложился в 30 секунд |

Коды самого YClients (`403` на зарплате у сотрудника без прав, `422` на
неверном диапазоне) проходят насквозь с его же телом — приложение уже умеет их
разбирать.

---

## Лимиты

| Что | Лимит | Зачем |
|---|---|---|
| `POST /v1/auth/login` | 5 попыток / 15 мин на `device_id` и на IP | Ключ приложения лежит в APK; без лимита это открытая дверь для перебора паролей YClients |
| Прокси-эндпоинты | 60 запросов / мин на устройство | Защита Pi и квоты YClients от зациклившегося клиента |
| Размер ответа прокси | 5 МБ | Страховка от неожиданно огромной выдачи |

Превышение — `429` с `Retry-After`, без блокировки устройства.

---

## Чего здесь нет

Эндпоинтов `neiro-push-events`, работающих по общему `API_KEY` и принимающих
токены YClients с телефона, в новом сервисе **не существует**. Обратная
совместимость не нужна: старое приложение до самого конца ходит в старый
сервис, который всё это время работает
([ROLLOUT.md](ROLLOUT.md#что-с-чем-сосуществует)).

| Было в `neiro-push-events` | Стало в `neiro-push` |
|---|---|
| `POST /v1/devices/register` (с `partner_token` и `user_token` в теле) | `POST /v1/auth/login` (логин и пароль, токены остаются на сервере) |
| `GET /v1/devices/{device_id}/events` | `GET /v1/events` |
| `POST /v1/devices/{device_id}/events/ack` | `POST /v1/events/ack` |
| `DELETE /v1/devices/{device_id}` | `POST /v1/auth/logout` |
| — | `GET /v1/session`, `POST /v1/devices/fcm`, шесть `/v1/yclients/*` |

Админские эндпоинты, `/health` и дашборд переносятся как есть, плюс две
кнопки: отзыв устройства и сброс аккаунта.
