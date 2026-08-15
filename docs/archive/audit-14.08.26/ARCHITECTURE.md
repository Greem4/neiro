# Архитектура Neiro на 14.08.2026

Слепок системы на дату аудита: из чего она состоит, кто с кем разговаривает, где
лежат данные и что покрыто тестами. Документ описывает **как есть**, а не как
задумано; расхождения с планами вынесены в [FINDINGS.md](FINDINGS.md).

Пути приложения сокращены от `app/src/main/java/ru/greemlab/neiro/`, пути
бэкенда — от корня репозитория. Номера строк — на коммит `0079df0`.

---

## 1. Общая картина

```
┌──────────────────────────────────────────────────────────────────────┐
│ Телефон: приложение Neiro (release, applicationId ru.greemlab.neiro) │
│                                                                      │
│   UI (Compose)   ──►  ViewModel  ──►  CalendarDataStore (DataStore)   │
│        ▲                                    │                        │
│        │                                    ▼                        │
│   notifications/  ◄──  sync/  ◄──  data/network/YClientsRepository    │
│        ▲                                    │                        │
│   push/ (FCM)                               │ device_token           │
│   update/ (GitHub)                          │                        │
└─────────┬───────────────────────────────────┼────────────────────────┘
          │ FCM                               │ HTTPS
          │                                   ▼
          │                    ┌──────────────────────────────────┐
          │                    │ VPS: nginx + TLS                 │
          │                    │   location /v1/ → туннель на Pi  │
          │                    └──────────────┬───────────────────┘
          │                                   │ reverse SSH
          │                    ┌──────────────▼───────────────────┐
          └────────────────────┤ Pi: neiro-push (FastAPI, SQLite) │
             session_events    │   auth · proxy · events · poller │
             sync_events       │   dashboard · fcm                │
                               └──────────────┬───────────────────┘
                                              │ partner_token + user_token
                                              ▼
                                     api.yclients.com
```

Приложение **не знает ключей YClients**. У него один секрет — `device_token`,
выданный сервисом при входе. Второй ключ, `NEIRO_PUSH_API_KEY`, запечён в APK и
открывает ровно одну дверь: `POST /v1/auth/login`.

Отдельный канал — [`update/`](../../../app/src/main/java/ru/greemlab/neiro/update):
приложение анонимно ходит на `api.github.com` за релизами и обновляет само себя.
Ни одного секрета туда не уезжает — свой OkHttp, отдельно от клиента сервиса.

Прежние поколения бэкенда (`server/`, `neiro-push-events/`) удалены из
репозитория 13.08.2026 и в границы аудита не входят.

---

## 2. Приложение: слои и пакеты

| Пакет | Файлов | Строк | За что отвечает |
|---|---:|---:|---|
| [`ui/`](../../../app/src/main/java/ru/greemlab/neiro/ui) | 56 | 16 367 | Экраны, диалоги, календарь, статистика, парсер записей |
| [`notifications/`](../../../app/src/main/java/ru/greemlab/neiro/notifications) | 24 | 2 997 | Планирование, дедуп и показ уведомлений, лента внутри приложения |
| [`data/`](../../../app/src/main/java/ru/greemlab/neiro/data) | 18 | 2 924 | DataStore, история ЗП, шифрованное хранилище сессии, сеть |
| [`update/`](../../../app/src/main/java/ru/greemlab/neiro/update) | 18 | 2 100 | Самообновление: проверка, загрузка, сверка, установка |
| [`sync/`](../../../app/src/main/java/ru/greemlab/neiro/sync) | 10 | 1 813 | Слияние записей YClients с календарём, история денег |
| [`push/`](../../../app/src/main/java/ru/greemlab/neiro/push) | 15 | 902 | FCM, догон событий, keepalive |
| [`theme/`](../../../app/src/main/java/ru/greemlab/neiro/theme) | 4 | 238 | Тема, системные бары |
| [`domain/`](../../../app/src/main/java/ru/greemlab/neiro/domain) | 4 | 189 | Модели: профиль, месяц, контекст цен |
| [`auth/`](../../../app/src/main/java/ru/greemlab/neiro/auth) | 1 | 42 | `LogoutCoordinator` — единственная точка выхода |

Внутри `ui/`: `components/` 7 291 · `profile/` 2 870 · `calendar/` 2 262 ·
`settings/` 1 853 · `screens/` 1 124 · `auth/` 584 · `sync/` 326 · `util/` 57.

Крупнейшие файлы (god-composables не дробятся — согласовано в
[OUT_OF_SCOPE](../audit-17.07.26/OUT_OF_SCOPE.md#33-разделение-god-composables)):
`ProfileYearStatsSection.kt` 1294 · `CalendarScreen.kt` 1124 ·
`DayDetailsDialog.kt` 1021 · `YClientsCalendarSync.kt` 1002 ·
`ProfileContent.kt` 807 · `YClientsRepository.kt` 784 ·
`SessionNotificationCoordinator.kt` 727 · `ScheduleSlotItem.kt` 705.

### Что изменилось с 30.07.2026

- **Появился пакет `update/`** — 18 файлов, 2 100 строк. Аудит не проходил.
- **Ключи YClients ушли с телефона.** `TokenStorage` хранит только
  `device_token`; `partner_token`, `user_token`, `company_id` и `staff_id`
  живут в `.env` на Pi. Старые ключи вычищаются из шифрованных prefs при каждом
  старте ([`TokenStorage.dropLegacyYClientsTokens`](../../../app/src/main/java/ru/greemlab/neiro/data/network/TokenStorage.kt)).
- **Версия переехала в `version.properties`** и считается формулой
  `major*10000 + minor*100 + patch` — одна и та же в Gradle и в
  [`ReleaseVersion.kt`](../../../app/src/main/java/ru/greemlab/neiro/update/ReleaseVersion.kt).
  Схема сменилась с `0.6.10.1` на `0.1.6`.
- **Два новых workflow**: `release-on-merge.yml` (поднять версию, поставить тег)
  и `release.yml` (собрать, подписать, опубликовать APK + `SHA256SUMS.txt`).

---

## 3. Хранение данных

| Хранилище | Механизм | Что внутри | Чистится |
|---|---|---|---|
| `calendar_data` | DataStore Preferences | `day_data_json` (календарь YClients), `saved_day_data_json` (архив), `user_profile_json`, `app_theme` | `clearAllData()` — только dev-сброс |
| `neiro_sync_cache` | SharedPreferences | Зеркало `day_data` / профиля / темы для первого кадра | вместе с DataStore |
| `neiro_yclients_secure` | EncryptedSharedPreferences | `device_token`, `staff_id`, логин, имя, аватар, `reauth_required`, `pending_revoke_token` | `TokenStorage.clear()` при logout (кроме `pending_revoke`) |
| `neiro_sync_prefs` | SharedPreferences | Метки синка, `live poll`, полного live-синка, флаги первичных заполнений | `clearSyncState()` при logout |
| `neiro_session_notifications` | SharedPreferences | Тумблеры, времена сводок, LRU показанных ключей (300), claim'ы дайджестов | частично при logout |
| `neiro_push_registrar` | SharedPreferences | `last_event_id` — курсор ленты событий | `PushEventsCursor.reset()` при logout |
| `neiro_push_device` | SharedPreferences | `device_id` (`neiro-<модель>-<androidId>`) | никогда |
| `neiro_update_prefs` | SharedPreferences | Метки проверок GitHub, `notified`/`skipped`/`pending` версии | не чистится при logout — намеренно |
| `neiro_session_meta` | SharedPreferences + Gson | `record_id`/`service_id` по ключу слота, лимит 20 000 | никогда; **никем не читается** (осознанно) |
| ЗП: `SalaryLedgerStore` | SharedPreferences + Gson | `months` (цена, факт, налог, `frozen`), `dailyFact` | не чистится при logout |

Ключ денежных данных — `staffId`: `SalaryLedger.monthKey(staffId, ym)`.

Резервные копии Android выключены полностью
([`backup_rules.xml`](../../../app/src/main/res/xml/backup_rules.xml),
[`data_extraction_rules.xml`](../../../app/src/main/res/xml/data_extraction_rules.xml)):
в облако не уезжает ничего, при переносе на новый телефон — только `datastore/`.

### Формат записи дня

Одна строка = одно занятие, поля через `|`:

```
Иванова Маша|3|10:00-10:50|+79990000000|комментарий      ← ученик
__DIAGNOSTICS__:2250|Петров Ваня|1|11:00-11:50           ← диагностика
__INTENSIVE__:=5600|Интенсив|3|14:00-15:30|Аня|3||;;Ваня|1||  ← интенсив с детьми
```

Коды статуса: `0` ожидание · `1` подтвердил · `2` отмена · `3` пришёл. В деньги
входит только `3`. Разбор — [`SessionParser`](../../../app/src/main/java/ru/greemlab/neiro/ui/calendar/SessionParser.kt),
`split("|", limit = 5)`: комментарий намеренно глотает хвост, поэтому дописать
поле в конец нельзя — для этого заведён `SessionMetaStore`.

---

## 4. Фон: воркеры и координаторы

| Работа | Имя | Тип | Политика | Кто ставит |
|---|---|---|---|---|
| Догон событий | `push_events_sync` | OneTime, expedited | `APPEND_OR_REPLACE` | нудж `sync_events` из FCM |
| Keepalive push | `push_keepalive` | OneTime, самопланирующийся | `KEEP` / `APPEND_OR_REPLACE` | `PushRegistrar`, вход, `finally` воркера |
| Тик уведомлений | `session_daily_notifications` | Periodic 15 мин | `KEEP` / `UPDATE` | `SessionNotificationCoordinator` |
| Напоминание о занятии | `session_reminder_<ключ>` | OneTime | `REPLACE` | diff-перепланирование |
| Сводки | `session_today_digest`, `session_tomorrow_digest`, `session_archive_digest` | OneTime, самопланирующиеся | `KEEP` / `REPLACE` / `APPEND_OR_REPLACE` | настройки, sync-путь |
| Проверка обновлений | `update_check` | Periodic 1 сутки | `KEEP` | `UpdateCheckCoordinator` |

Самопланирование живёт в `finally { if (!isStopped) scheduleNext() }` —
[`PushKeepAliveWorker`](../../../app/src/main/java/ru/greemlab/neiro/push/PushKeepAliveWorker.kt),
[`SessionScheduledDigestWorker`](../../../app/src/main/java/ru/greemlab/neiro/notifications/SessionScheduledDigestWorker.kt).
`Result.retry()` рядом с ним нигде не стоит: две системы планирования на одну
цепочку удлиняли очередь на каждой ошибке.

Локального опроса YClients по таймеру нет с 25.07.2026 — изменения приходят
push'ом. `LiveApiCoordinator` подтягивает календарь только при входе и при
возврате в приложение, с накопительной паузой при недоступном сервере
([`NetworkRetryBackoff`](../../../app/src/main/java/ru/greemlab/neiro/sync/NetworkRetryBackoff.kt)).

Интервалы keepalive: 30 мин днём, 60 мин в тихие часы (21:00–09:00 МСК,
[`SyncQuietHours`](../../../app/src/main/java/ru/greemlab/neiro/sync/SyncQuietHours.kt)),
5 мин после неудачи.

---

## 5. Уведомления

Три источника, один показ:

1. **Push с сервера** (`session_events`) → `PushEventCalendarApplier` правит
   календарь → `PushEventNotifier` показывает.
2. **Догон** (`GET /v1/events`) → то же самое, страницами по 100, максимум 10
   страниц за цикл.
3. **Локальный diff** после синка → `SessionChangeDetector` → те же события.

Дедуп общий: `SessionEvent.dedupeKey`, LRU на 300 ключей в
`SessionNotificationPreferences`. Отметка «показано» ставится **только после
успешного** `notify()` — `SecurityException` откатывает claim.

Лента внутри приложения (`InAppNotificationStore`, `ArchiveNotificationStore`)
ведётся **всегда**, даже без `POST_NOTIFICATIONS`: проверка разрешения стоит
внутри `SessionNotificationDisplay`, после записи в ленту.

Каналы: `neiro_sessions` (важность HIGH, занятия) и `app_updates` (важность
DEFAULT, новые версии). Идентификаторы уведомлений: 10 001–10 005 занятия,
10 006–10 007 обновление, динамические от 20 000.

---

## 6. Деньги

Модель после разворота 01.08.2026: **начисление YClients — источник правды за
прошлое**, профиль отвечает за текущий и будущие месяцы, ручная цена месяца
перекрывает факт.

```
resolveMonthRates(месяц)
   ├── месяц ≥ текущего            → цены профиля
   ├── entry.origin == MANUAL      → цена человека
   ├── entry.pricePerSession > 0   → цена из позиций начисления
   └── иначе                       → (факт − диагностики − интенсивы) ÷ занятия
```

`mergeFact` замораживает месяц через 7 дней после его конца (`frozen`), и с этого
момента синк его не трогает. Разморозка — кнопка в разборе месяца.

Цена занятия берётся из **позиций** начисления, а не делением: в августе 2025
одновременно шли ставки 1250 и 1400, и деление давало 1355 — число, которого не
было ни у одного занятия.

---

## 7. Самообновление

```
UpdateCheckWorker (раз в сутки) ─┐
ProcessLifecycle onStart ────────┼─► UpdateCheckCoordinator.checkNow
кнопка «Проверить» ──────────────┘            │
                                              ▼
                          UpdateChecker: гейт канала → троттлинг 24 ч
                          → GET /repos/Greem4/neiro/releases/latest
                          → разбор тега vX.Y.Z → сравнение versionCode
                                              │
                          ┌───────────────────┴──────────────┐
                          ▼                                  ▼
                  UpdateNotifier                      экран «О программе»
             (канал app_updates, «Пропустить»)      кнопка «Обновить»
                                                            │
                                     UpdateDownloader (cacheDir/updates)
                                                            │
                                     UpdateVerifier: SHA-256 + подпись APK
                                                            │
                                     ApkInstaller: PackageInstaller Session
                                     USER_ACTION_NOT_REQUIRED на API 31+
                                                            │
                                     UpdateInstallReceiver: SUCCESS /
                                     PENDING_USER_ACTION / FAILURE
```

Гейт канала (`UpdateChannelGate`) выключает обновление для debug, prerelease и
установок из RuStore / Google Play. Скачивание не запускается автоматически
никогда — только по нажатию.

Контракт релиза, который держит `release.yml`: тег `vX.Y.Z` совпадает с
`version.properties`, ассеты `neiro-<версия>.apk`, `SHA256SUMS.txt`,
`mapping-<версия>.txt.gz`.

---

## 8. Бэкенд `neiro-push`

| Файл | Строк | Что делает |
|---|---:|---|
| [`app/database.py`](../../../neiro-push/app/database.py) | 1010 | SQLite: схема, миграции колонок, ретеншен, запросы дашборда |
| [`app/dashboard.py`](../../../neiro-push/app/dashboard.py) | 471 | Сбор и форматирование данных дашборда (HTML и текст) |
| [`app/main.py`](../../../neiro-push/app/main.py) | 457 | FastAPI, admin API, дашборд, cookie-авторизация |
| [`app/poller.py`](../../../neiro-push/app/poller.py) | 413 | Цикл опроса YClients, backoff, отправка пушей |
| [`app/yclients.py`](../../../neiro-push/app/yclients.py) | 370 | Клиент YClients, разбор записей, подбор сотрудника по имени |
| [`app/auth.py`](../../../neiro-push/app/auth.py) | 295 | Вход, `device_token`, `/v1/session`, обновление FCM-токена |
| [`app/proxy.py`](../../../neiro-push/app/proxy.py) | 200 | Семь GET-эндпоинтов YClients насквозь |
| [`app/events.py`](../../../neiro-push/app/events.py) | 170 | Чистый дифф состояний записей → события |
| [`app/fcm.py`](../../../neiro-push/app/fcm.py) | 126 | HTTP v1 FCM, нудж при payload > 3 КБ |
| [`app/device_events.py`](../../../neiro-push/app/device_events.py) | 82 | `GET /v1/events`, `POST /v1/events/ack` |
| [`app/ratelimit.py`](../../../neiro-push/app/ratelimit.py) | 78 | Скользящее окно в памяти процесса |
| [`app/schemas.py`](../../../neiro-push/app/schemas.py) | 72 | Pydantic-модели запросов и ответов |
| [`app/security.py`](../../../neiro-push/app/security.py) | 54 | Хэш токена, Fernet, сравнение за постоянное время |
| [`app/config.py`](../../../neiro-push/app/config.py) | 36 | Настройки из `.env` |

### Схема БД

```
accounts (id, company_id, staff_id, user_token_enc, reauth_required,
          auth_failures, changed_after, backoff_until, consecutive_errors, …)
   │ UNIQUE(company_id, staff_id)
   ├──< devices (device_id UNIQUE, token_hash UNIQUE, revoked_at, fcm_token,
   │             last_ack_event_id, last_seen_at)             ON DELETE CASCADE
   ├──< record_states (PK: account_id + record_id)            ON DELETE CASCADE
   └──< events (id AUTOINCREMENT — сквозной по всем аккаунтам) ON DELETE CASCADE
            │
            └──< push_deliveries (event_id, device_id, status)   без FK

poll_runs (company_id, started_at, duration_ms, records_fetched,
           events_created, pushes_sent, error)
```

`PRAGMA foreign_keys=ON` и `busy_timeout=5000` ставятся на каждое соединение,
`journal_mode=WAL` — один раз при создании схемы. Ретеншен: события, доставки и
циклы старше 90 дней, раз в час. `record_states` ретеншен не трогает.

### Три роли доступа

| Ключ | Что открывает |
|---|---|
| `API_KEY` (в APK) | только `POST /v1/auth/login` |
| `device_token` (выдан при входе) | `/v1/session`, `/v1/events*`, `/v1/yclients/*`, `/v1/devices/fcm` |
| `ADMIN_API_KEY` (только на Pi) | `/health`, `/v1/admin/*`, дашборд |

Отказы: `401` — токена нет или отозван, приложение делает полный logout;
`409` — протух `user_token` на сервере, сессия жива, нужен только пароль.

### Цикл опроса

```
poll_once → аккаунты без reauth_required → группировка по company_id
   └─ _poll_company:
        seeding? (у кого-то нет record_states) → полный горизонт, без событий
        иначе   → changed_after = min(курсоров), with_deleted=1
        один запрос на компанию, перебор user_token до первого рабочего
        └─ _poll_account (на каждый staff_id):
             derive_events(previous, records) → события + новые состояния
             commit_poll_result: события и состояния одной транзакцией
             push всем устройствам аккаунта с непустым fcm_token
```

Горизонт запроса — сегодня + `HORIZON_DAYS` (62). Интервал: `POLL_INTERVAL_SECONDS`
днём (на Pi 15 с), `POLL_NIGHT_INTERVAL_SECONDS` с `QUIET_START_HOUR` до полуночи.

---

## 9. Тесты

| Часть | Файлов | Строк | Чем покрыто |
|---|---:|---:|---|
| `app/src/test` | 44 | 5 375 | Парсер, статистика, деньги, уведомления, push-события, обновление |
| `app/src/androidTest` | 1 | — | Заглушка |
| `neiro-push/tests` | 9 | 2 632 | `auth`, `ratelimit`, `main`, `poller`, `proxy`, `events`, `device_events`, `database`, `yclients` |

Тесты обновления: `ReleaseVersionTest`, `UpdateCheckerTest`,
`Sha256SumsParserTest`, `AssetPickerTest`, `UpdateNotificationPolicyTest` — вся
чистая логика пакета `update/` вынесена под них.

CI (`ci.yml`) гоняет `./gradlew testDebugUnitTest` и запрещает артефакты в
репозитории. `pytest` бэкенда в CI **не запускается** — см.
[B2](FINDINGS.md#b2-средне--ci-не-гоняет-тесты-бэкенда).

---

## 10. Метрики базы на 14.08.2026

| Часть | Файлы | Строк | Тесты |
|---|---:|---:|---|
| `app/src/main` (Kotlin) | 152 | 27 814 | 45 файлов / 5 398 строк |
| `neiro-push/app` (Python) | 15 | 3 834 | 9 файлов / 2 632 строки |
| `neiro-push/templates` | 6 | — | — |
| Ресурсы `app/src/main/res` | 25 | — | — |

Ветка `аудит-6`, версия `0.1.6` (`versionCode` 106), `compileSdk`/`targetSdk` 35,
`minSdk` 24, AGP 9.2.1, Kotlin 2.2.10, Compose BOM 2024.10.00 с ядром,
закреплённым на 1.9.0, Firebase BOM 33.7.0.

Дельта с 30.07.2026: `app/src/main` 21 749 → 27 814 строк (+28 %), почти весь
прирост — новый пакет `update/` (2 100) и разросшиеся `ui/profile` и
`ui/components`. Бэкенд: два сервиса на 6 162 строки заменены одним на 3 834,
тесты выросли с ~1 126 до 2 632 строк.
