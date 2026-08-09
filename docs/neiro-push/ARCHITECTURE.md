# Архитектура: токены на сервере

Кто что хранит, как проходит вход, как устроен прокси и что меняется в коде с
обеих сторон. Спецификация эндпоинтов — [API.md](API.md), инфраструктура —
[DEPLOY.md](DEPLOY.md), порядок запуска — [ROLLOUT.md](ROLLOUT.md).

## Два режима приложения

Разделение, которое сейчас существует неявно, становится явным.

**Локальный режим — по умолчанию, без регистрации.** Календарь-архив, ручные
занятия, статусы, деньги по ставкам профиля, экспорт и импорт JSON,
уведомления-напоминания о занятиях из локальных данных. Ни одного сетевого
запроса к YClients и к Pi (кроме проверки обновлений). Ни одного секрета на
устройстве.

**Подключённый режим — после входа в YClients.** Всё то же плюс: расписание из
YClients, начисленная зарплата, push об изменениях. Требует Pi: и за данными,
и за уведомлениями телефон ходит туда.

Переключение — вход и выход в существующем экране авторизации. Выход
возвращает приложение в локальный режим и стирает `device_token`; локальные
данные остаются.

## Кто что хранит

| Секрет | Раньше | Теперь |
|---|---|---|
| `partner_token` YClients | `BuildConfig` + `EncryptedSharedPreferences` на телефоне, копия на Pi в `accounts` | Только `.env` на Pi, одно значение на весь сервис |
| `user_token` YClients | `EncryptedSharedPreferences` на телефоне, копия на Pi | Только Pi, шифрованно (`SecretBox`, Fernet) |
| Пароль YClients | Не хранится (вводится при входе) | Не хранится нигде, живёт только внутри одного запроса |
| `company_id`, `staff_id` | `BuildConfig` и prefs на телефоне | Pi, в `accounts`; телефону приходят справочно |
| `device_token` | — | Телефон: `EncryptedSharedPreferences`. Pi: только SHA-256 хэш |
| Ключ приложения к Pi | `BuildConfig.NEIRO_PUSH_API_KEY` | Там же — но теперь открывает только вход |

Смысл перестановки: секрет, который нельзя отозвать (`partner_token`), уходит
туда, где его никто не видит; секрет, который остаётся на телефоне
(`device_token`), сделан узким и отзываемым.

## Вход

```
Пользователь                Приложение                    Pi                       YClients
     │  логин + пароль          │                          │                          │
     ├─────────────────────────►│                          │                          │
     │                          │ POST /v1/auth/login      │                          │
     │                          │ Bearer <APP_KEY>         │                          │
     │                          ├─────────────────────────►│                          │
     │                          │                          │ POST /auth               │
     │                          │                          │ Bearer <partner_token>   │
     │                          │                          ├─────────────────────────►│
     │                          │                          │◄──── user_token, имя ────┤
     │                          │                          │ GET /book_staff/{company}│
     │                          │                          ├─────────────────────────►│
     │                          │                          │◄──── staff_id по имени ──┤
     │                          │            device_token  │ шифрует user_token,      │
     │                          │◄─────────────────────────┤ пишет accounts+devices   │
     │◄── вошли, идёт синк ─────┤                          │                          │
```

Три вещи, которые сервер делает за один вход и которые сейчас размазаны по
клиенту:

1. **Логинится в YClients** своим `partner_token` — телефон пароль дальше Pi
   не посылает, а Pi его не сохраняет (и не пишет в лог, см.
   [RISKS.md](RISKS.md#пароль-проходит-через-свой-сервер)).
2. **Определяет `staff_id`** по совпадению имени пользователя с карточкой в
   `book_staff` — та самая логика, что сейчас живёт в
   `YClientsRepository.detectAndSaveStaffId`. Не совпало — вход отклоняется с
   понятной причиной, а не с пустым календарём потом.
3. **Регистрирует устройство** — `fcm_token`, `device_id`, метка, версия
   приложения. Отдельный вызов регистрации после логина больше не нужен: это
   один и тот же момент.

Ответ: `device_token` (случайные 32 байта, base64url), имя пользователя,
аватар, `staff_id`, `company_id`, начальный `last_event_id`.

## Прокси

Приложению нужно семь методов YClients — ровно те, что перечислены в
`YClientsApi.kt`. Каждому соответствует эндпоинт на Pi:

| Приложение | Pi | YClients |
|---|---|---|
| `getRecords` | `GET /v1/yclients/records` | `/records/{company_id}` |
| `getClients` | `GET /v1/yclients/clients` | `/clients/{company_id}` |
| `getBookStaff` | `GET /v1/yclients/staff` | `/book_staff/{company_id}` |
| `getSalaryDaily` | `GET /v1/yclients/salary/daily` | `/company/…/salary/period/staff/daily/…` |
| `getSalaryCalculations` | `GET /v1/yclients/salary/calculations` | `/company/…/salary/payroll/…/calculation/` |
| `getSalaryCalculationDetails` | `GET /v1/yclients/salary/calculations/{id}` | то же `/{calculation_id}` |
| `auth` | `POST /v1/auth/login` | `/auth` (внутри сервера) |

Три правила, на которых стоит вся конструкция:

**Тело ответа пробрасывается как есть.** Прокси не разбирает `data`, не
переименовывает поля, не «улучшает» формат — отдаёт JSON YClients байт в байт
вместе с кодом ответа. Иначе пришлось бы переписать `YClientsModels`,
`SessionParser` и всё, что на них завязано, — недели работы и лучший способ
испортить то, что работает. Приложение меняет только базовый URL и заголовок.

**`company_id` и `staff_id` подставляет сервер.** Их нет ни в пути, ни в
параметрах запроса от телефона. Приложение больше не может случайно (или
намеренно) попросить чужие записи: `device_token` жёстко привязан к аккаунту.
Заодно исчезает клиентская страховка «фильтруем чужих на клиенте» — фильтр
теперь не обойти.

**Пагинация остаётся на клиенте.** `page` и `count` пробрасываются как есть;
цикл по страницам в `YClientsRepository` не трогаем. Собирать страницы на
сервере — соблазнительно и означает переписать работающий код ради красоты.

## Что происходит при отказах

| Ситуация | Ответ Pi | Что делает приложение |
|---|---|---|
| `device_token` неизвестен или отозван | `401` | Выход в локальный режим, экран входа |
| `user_token` протух (YClients ответил 401) | `409 reauth_required` | «Сессия YClients истекла, войдите ещё раз» — пароль запрашивается заново |
| YClients недоступен или ответил 5xx | `502` | «YClients недоступен», данные из локального кэша |
| Pi недоступен | нет ответа | «Нет связи с сервером Neiro», локальный кэш, повтор с backoff |
| Слишком часто | `429` | Пауза до `Retry-After` |
| Права не позволяют (зарплата у сотрудника) | код YClients как есть (`403`) | Как сегодня |

Разница между `401` и `409` принципиальна: первый значит «телефон больше не
имеет доступа» (полный выход), второй — «нужен пароль от YClients» (аккаунт
жив, устройство зарегистрировано, пуши продолжают идти).

Сервер сам не перелогинивается: пароля у него нет и быть не должно. Аккаунту
ставится флаг `reauth_required`, поллер такой аккаунт пропускает и не жжёт
запросы впустую, а пользователь видит уведомление.

## Устройство сервера

Новый сервис `neiro-push` — отдельный каталог, отдельная БД, отдельный порт;
работающий `neiro-push-events` не трогается вовсе
([DEPLOY.md](DEPLOY.md#три-поколения-сервиса)). Схема доступа пишется с нуля,
обкатанный код границ YClients переносится как есть.

### Схема БД

Создаётся сразу в нужном виде — мигрировать нечего, база новая и пустая:

```sql
CREATE TABLE accounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    company_id INTEGER NOT NULL,
    staff_id INTEGER NOT NULL,
    user_login TEXT,                                  -- подсказка при повторном входе
    user_name TEXT,                                   -- имя из /auth, его же отдаёт /v1/session
    avatar_url TEXT,                                  -- то же, для карточки в профиле
    user_token_enc TEXT NOT NULL,                     -- Fernet, ключ в .env
    reauth_required INTEGER NOT NULL DEFAULT 0,       -- user_token протух, нужен пароль
    last_auth_at TEXT,
    changed_after TEXT,
    backoff_until TEXT,
    consecutive_errors INTEGER NOT NULL DEFAULT 0,
    last_polled_at TEXT,
    last_error TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(company_id, staff_id)
);

CREATE TABLE devices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    device_id TEXT NOT NULL UNIQUE,
    token_hash TEXT NOT NULL UNIQUE,                  -- sha256(device_token)
    revoked_at TEXT,
    fcm_token TEXT NOT NULL,
    label TEXT,
    app_version TEXT,
    last_ack_event_id INTEGER,
    last_seen_at TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY(account_id) REFERENCES accounts(id) ON DELETE CASCADE
);
```

`record_states`, `events`, `push_deliveries`, `poll_runs` переносятся из
`neiro-push-events` без изменений — на них завязан поллер, который тоже
переносится целиком.

Главное отличие от прежней схемы: **колонки `partner_token_enc` нет**.
Партнёрский токен один на весь сервис и живёт в `.env`; в таблице аккаунтов он
оказался только потому, что раньше приезжал с телефона.

### Новые модули

```
neiro-push/app/
  auth.py       — вход, выпуск и проверка device_token, зависимость require_device
  proxy.py      — семь прокси-эндпоинтов, единый обработчик ошибок YClients
  ratelimit.py  — счётчики попыток входа и запросов на устройство
  yclients.py   — + методы login, fetch_staff, fetch_records_raw, fetch_clients_raw,
                    fetch_salary_* (сырой JSON, без разбора)
```

Полная раскладка каталога и что откуда переносится —
[DEPLOY.md § Структура сервиса](DEPLOY.md#структура-сервиса).

`poller.py` меняется в двух местах: берёт `partner_token` из настроек, а не из
аккаунта, и пропускает аккаунты с `reauth_required`.

Дашборд получает две кнопки: **отозвать устройство** и **сбросить аккаунт**
(потребовать повторный вход). Сегодня отозвать доступ у телефона нечем.

### Настройки `.env`

```bash
YCLIENTS_PARTNER_TOKEN=…   # новый: единственное место, где он теперь есть
YCLIENTS_COMPANY_ID=…      # новый: филиал по умолчанию для входа
API_KEY=…                  # прежний, теперь только для /v1/auth/login
ADMIN_API_KEY=…            # прежний, дашборд и health
TOKEN_ENCRYPTION_KEY=…     # прежний, шифрование user_token
```

## Изменения в приложении

| Файл | Что происходит |
|---|---|
| `data/network/YClientsClient.kt` | Базовый URL — сервис на Pi; заголовок `Authorization: Bearer <device_token>` вместо пары токенов YClients |
| `data/network/YClientsApi.kt` | Пути меняются на прокси-эндпоинты, из сигнатур уходят `companyId` и `staffId` |
| `data/network/TokenStorage.kt` | `partnerToken` и `companyId` удаляются, появляется `deviceToken`; `userToken` уходит |
| `data/network/YClientsRepository.kt` | `login` идёт в Pi; `detectAndSaveStaffId` удаляется (делает сервер); обработка `401`/`409` |
| `push/PushRegistrar.kt` | Регистрация устройства сливается со входом; `partner_token`/`user_token` из запроса уходят; остаётся обновление `fcm_token` |
| `push/PushApi.kt`, `push/PushClient.kt` | Авторизация по `device_token`, `device_id` больше не в пути |
| `ui/auth/AuthScreen.kt`, `AuthViewModel.kt` | Форма `PartnerTokenSetup` удаляется целиком — вводить нечего |
| `app/build.gradle.kts` | `YCLIENTS_PARTNER_TOKEN` и `YCLIENTS_COMPANY_ID` из `BuildConfig` удаляются |

Что **не** трогается: `SessionParser`, `YClientsModels`, `YClientsCalendarSync`,
`SalaryLedger`, весь календарь, архив, расчёт денег, уведомления о занятиях.
Формат ответов не изменился — значит и разбор не меняется.

Отдельно: локального опроса YClients по таймеру нет и не появляется
(`LiveApiCoordinator` подтягивает данные при входе и при возврате в
приложение). Нагрузка на Pi остаётся редкой и предсказуемой.

## Кэш на сервере — потом, не сейчас

Соблазн: поллер и так каждые 10 секунд тянет записи филиала, можно отдавать
`/records` из его снимка. Но поллер держит горизонт «сегодня + 62 дня» и
хранит только те поля, что нужны для событий, а приложению нужны прошлые
месяцы и полный JSON. Совмещать — переписывать поллер.

Разумный минимум, если Pi начнёт задыхаться: короткий кэш ответов (30–60 с) по
ключу «аккаунт + путь + параметры». Он снимает шквал одинаковых запросов от
нескольких устройств и не требует ничего знать о содержимом. В план это не
включено намеренно: одно устройство такой нагрузки не создаёт.
