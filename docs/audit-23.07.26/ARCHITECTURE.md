# Архитектура приложения Neiro — состояние на 23.07.26

Полное описание архитектуры по итогам глубокого аудита. Версия приложения — `0.6.9.0` (versionCode 2), ветка `аудит-3`. Кодовая база: ~21 400 строк Kotlin в `app/src/main` + ~2 070 строк unit-тестов.

Документ — справочник: слои, компоненты, потоки данных, схема хранения, фоновые задачи, уведомления, сборка. Находки и план исправлений — в [FINDINGS.md](FINDINGS.md) и [ROADMAP.md](ROADMAP.md).

---

## 1. Общая картина

Приложение — календарь занятий нейропсихолога с синхронизацией из YClients, локальным «личным» архивом, статистикой доходов и push-уведомлениями через собственный сервер (`push.neiro.greemlab.ru`) + FCM.

Один Gradle-модуль `app`, один Activity, без NavHost и без DI-фреймворка (синглтоны через double-checked locking — осознанное решение, см. `docs/audit-17.07.26/OUT_OF_SCOPE.md`).

```
┌────────────────────────── UI (Compose, ~14 200 строк) ──────────────────────────┐
│ MainActivity → CalendarScreen (корень)                                          │
│   ├─ ModalNavigationDrawer → ProfileContent (годовая статистика)                │
│   └─ CalendarOverlay (sealed): DayDetails / Settings / AppSettings /            │
│      NotificationSettings / ProfitSettings / YClients(Auth) / Notifications     │
│ ViewModels: Calendar / Profile / Auth / Sync / AppSettings / SessionNotif.      │
└──────────────────────────────────┬──────────────────────────────────────────────┘
                                   │ StateFlow (stateIn / collectAsStateWithLifecycle)
┌──────────────────────────────────▼──────────────────────────────────────────────┐
│ Данные:   CalendarRepository → CalendarDataStore (Preferences DataStore         │
│           "calendar_data" + sync-зеркало SharedPreferences "neiro_sync_cache")  │
│ Сеть:     YClientsRepository → YClientsClient (Retrofit/OkHttp)                 │
│           TokenStorage (EncryptedSharedPreferences "neiro_yclients_secure")     │
└──────────────────────────────────┬──────────────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼──────────────────────────────────────────────┐
│ Фон:      YClientsCalendarSync (единый Mutex на все merge)                      │
│           LiveApiCoordinator / AutoSyncCoordinator / PushRegistrar              │
│           WorkManager: LiveApiRefreshWorker, PushSyncWorker,                    │
│           PushKeepAliveWorker, Session*Worker'ы                                 │
│ Push:     NeiroFirebaseMessagingService (FCM) + PushApi (свой сервер)           │
│ Уведомл.: SessionNotificationCoordinator + SessionChangeDetector                │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Размеры пакетов (строк Kotlin)

| Пакет | LOC | Содержание |
|---|---|---|
| `ui/` | 14 208 | Compose-экраны, ViewModels, SessionParser, статистика |
| `notifications/` | 2 903 | Уведомления: события, дайджесты, напоминания, in-app лента |
| `data/` | 1 613 | DataStore, репозитории, сеть YClients, TokenStorage |
| `sync/` | 1 374 | Синхронизация календаря, координаторы, воркеры |
| `push/` | 462 | FCM, регистрация устройства, keepalive |
| `theme/`, `domain/`, `auth/` | 388 | Тема, модели, LogoutCoordinator |

Самые крупные файлы (god-composables — осознанно не дробятся): `CalendarScreen.kt` (1029), `DayDetailsDialog.kt` (1019), `YClientsCalendarSync.kt` (924), `ProfileContent.kt` (750), `ScheduleSlotItem.kt` (692), `ProfileYearStatsSection.kt` (677), `SessionNotificationCoordinator.kt` (659), `SessionParser.kt` (526).

---

## 2. Слой данных

### 2.1 Двухслойное хранение календаря

Авторитетный источник — Preferences DataStore `calendar_data` (`preferences_pb`, с `corruptionHandler → emptyPreferences()`). Рядом — синхронное зеркало SharedPreferences `neiro_sync_cache`, нужное только для мгновенного `peek` на холодном старте (пока DataStore не прочитан).

| Ключ | Содержимое | Формат |
|---|---|---|
| `day_data_json` | Синхронизированный календарь | JSON `{"yyyy-MM-dd": ["строка слота", ...]}` |
| `saved_day_data_json` | Личный архив (вечное хранение) | тот же формат |
| `user_profile_json` | `UserProfile` (цены, налог, рабочие дни, `isRegistered`) | Gson через `UserProfileJson` |
| `app_theme` | `system` / `light` / `dark` | строка |

Особенности:

- **Полная история хранится на телефоне** и не перезапрашивается; активно синхронизируется только «живой» диапазон (текущий + следующий месяц).
- `writeMutex` сериализует все записи; `updateDayData`/`updateProfile` делают read-modify-write **внутри** `dataStore.edit` (атомарно). Исключение — `applySessionPriceChange` и архивные операции, читающие `cachedState` (архив — by design, цена — находка D2).
- `snapshotsFlow`: `dataStore.data → parseSnapshot (Gson) → side-effect (cachedState + writeSyncCache) → flowOn(Default)`. Четыре публичных flow (theme/dayData/savedDayData/profile) — производные от этого **cold** flow без `shareIn` (находка D3).
- Sync-кэш намеренно **не** пишет `saved_day_data_json` (архив не зеркалируется), но `loadFromSyncCache` его всё ещё читает (находка D4).
- `CalendarDataStoreProvider` — DCL-синглтон с sync peek-хелперами (`peekProfile`, `peekDayData`, ...).

### 2.2 Сеть YClients

| Класс | Роль |
|---|---|
| `YClientsApi` | Retrofit: `auth`, `getRecords`, `getClients`, `getRecord` (мёртвый), `getBookStaff` |
| `YClientsClient` | Синглтон OkHttp+Retrofit: auth-interceptor, `RetryInterceptor` (408/429/5xx + `IOException`, 3 попытки, backoff 500·2ⁿ мс через `Thread.sleep`), таймауты 30/30/30 + `callTimeout` 60 с, DEBUG-логирование с redact `Authorization` |
| `YClientsModels` | DTO; критичные поля nullable (`date`, `userToken`), битые записи отфильтровываются `isValidRecord` |
| `YClientsRepository` | Фасад: login/logout, пагинация по `meta.totalCount`, определение `staffId` по токенам имени, фильтр записей, 401 → `tokenStorage.clear()` + асинхронный `LogoutCoordinator.logout` с `AtomicBoolean`-защитой |
| `TokenStorage` | `EncryptedSharedPreferences "neiro_yclients_secure"`; при ошибке — wipe keyset + master key и повтор; при повторном провале — `InMemoryPrefs` (на диск не пишется); legacy plaintext-файл удаляется. `StateFlow` для login/avatar |

Ключи `TokenStorage`: `user_token`, `user_login`, `user_name`, `user_avatar_url`, `partner_token`, `company_id`, `staff_id`, `staff_id_company`. `partnerToken`/`companyId` при пустых prefs берутся из `BuildConfig` (компромисс, зафиксирован в OUT_OF_SCOPE прошлого аудита). `clear()` не трогает partner/company.

Все сетевые методы — `withContext(IO)`, `CancellationException` пробрасывается.

---

## 3. Синхронизация

Все пути merge сходятся в `YClientsCalendarSync` под одним `Mutex`.

| Путь | Триггер | Метод | Watermark (`SyncPreferences`, `neiro_sync_prefs`) |
|---|---|---|---|
| Ручной / после логина | `SyncViewModel` | `syncMonth` / `syncDateRange` / `syncDefaultAutoRange` / полная история | `lastSyncEpoch`, `hasCompletedInitialFullSync` |
| Суточный авто | `AutoSyncCoordinator` (foreground, устарело >24 ч) | `syncDefaultAutoRange()` | `lastSyncEpoch` |
| Live / FCM / keepalive | `refreshLiveRange()` | инкремент `changed_after` (overlap 60 с, `with_deleted=1`) или полный раз в 6 ч | `lastLivePollEpoch`, `lastFullLiveSyncEpoch` |

Live-диапазон — текущий + следующий месяц. Инкремент по текущему месяцу перезапрашивает полный снимок только окна `min..max` затронутых дат (authoritative wipe в этом окне). Прочие месяцы — точечный merge без очистки. Текущий месяц авторитетен из API: локальные записи без пары в YClients удаляются (by design).

### Два режима работы

- **Push активен** (`PushConfig.isActive` = сервер сконфигурирован + FCM): фоновая цепочка `LiveApiRefreshWorker` не планируется; опора на FCM → `PushSyncWorker`, страховка — `PushKeepAliveWorker` (30/60 мин: перерегистрация + `refreshLiveRange`). Foreground-опроса нет (находка S3).
- **Push неактивен**: foreground coroutine-poll (5 мин) + фоновая самоперепланирующаяся цепочка `LiveApiRefreshWorker` (5 мин днём МСК 09–21, 60 мин ночью).

### Воркеры WorkManager

| Unique name | Класс | Политика | Период / constraints |
|---|---|---|---|
| `yclients_live_api_refresh` | `LiveApiRefreshWorker` | старт `KEEP`; self-reschedule `APPEND_OR_REPLACE` | 5/60 мин, `CONNECTED`, `Result.retry()` на Failure |
| `push_keepalive` | `PushKeepAliveWorker` | старт `KEEP`; self-reschedule `APPEND_OR_REPLACE` | 30/60 мин, `CONNECTED`, всегда `success` |
| `push_fcm_sync` | `PushSyncWorker` | `APPEND_OR_REPLACE` | one-shot expedited, `CONNECTED`, `retry()` на Failure |
| `session_daily_notifications` | `SessionDailyNotificationWorker` | Periodic 15 мин, `UPDATE` | без constraints |
| `session_{today,tomorrow,archive}_digest` | `SessionScheduledDigestWorker` | sync→`KEEP`; self→`APPEND_OR_REPLACE`; UI→cancel+enqueue | one-time на точное время |
| `session_reminder_{dedupeKey}` | `SessionReminderWorker` | `REPLACE`, tag `session_reminder` | one-time, горизонт ≤7 дней |

### Push-регистрация (жизненный цикл)

1. **Login** → `detectAndSaveStaffId` → `PushRegistrar.onLoginSuccess` → FCM-токен → `POST v1/devices/register` (3 retry с backoff, 4xx без retry) → keepalive `schedule(KEEP)`.
2. **Foreground / boot / keepalive** → повторный register с актуальным токеном; **`onNewToken`** → `registerWithToken`.
3. **Logout** (`auth/LogoutCoordinator`) → cancel legacy periodic + live worker + keepalive → `SessionNotificationCoordinator.onLoggedOut` → suspend `DELETE v1/devices/{deviceId}` → `repository.logout()` → `clearSyncState()`. FCM-sync воркер не отменяется (находка S5).

---

## 4. Подсистема уведомлений

Один канал `neiro_sessions` (`IMPORTANCE_HIGH`); group keys `neiro_sessions_events` / `neiro_sessions_reminders`; фиксированные id 10001–10005, динамические `20000 + hash % 1e8`.

| Тип | Триггер | Push | In-app лента |
|---|---|---|---|
| События (`NEW_BOOKING`, `CANCELLED`, `RESCHEDULED`, `DELETED`, `CLIENT_CONFIRMED`, `CLIENT_ARRIVED`) | diff before/after внутри одного sync (`SessionChangeDetector`, горизонт 60 дней, только сегодня+) | да | да |
| Напоминание за N мин (15/30/45/60, по умолчанию выкл.) | one-time `SessionReminderWorker` + fallback через 15-мин periodic (окно ±7 мин) | да | да |
| Сводка «сегодня» (08:00) / «завтра» (20:00) / «архив» (21:00) | one-time digest-воркеры + periodic + проверка на открытии приложения | да | да |
| Архивный журнал | копия каждой записи в `ArchiveNotificationStore` (лимит 5000) | — | отдельные prefs |

Dedupe/claim:

- события/напоминания: `was*Notified` → показ → `mark*Notified` (LRU-список, max 300);
- дайджесты: `claimToday/TomorrowDigest` (`@Synchronized`, `commit`); при неудачном показе claim откатывается;
- архив: claim по epochDay в упорядоченном LRU.

Хранилища: `neiro_session_notifications` (тумблеры, времена, dedupe-ключи, `calendar_snapshot` — пишется, но не читается), `in_app_notifications` (300 шт. / 10 дней), `neiro_archive_notifications` (5000 шт.).

Путь sync → уведомления: `YClientsCalendarSync` передаёт `dayDataBefore/After` → `onCalendarUpdatedFromApi` → detect → показ событий + mark → `scheduleAfterBaseline` (перепланирование напоминаний, суточного periodic, дайджестов `KEEP`, немедленные проверки). Deep link уведомлений: `EXTRA_OPEN_DATE` + `EXTRA_HIGHLIGHT_SLOT_KEY`.

---

## 5. UI-слой

### 5.1 Навигация

NavHost отсутствует. Корень — `CalendarScreen`; профиль — `ModalNavigationDrawer` (контент компонуется только при `isOpen || isAnimationRunning`); всё остальное — sealed `CalendarOverlay` поверх того же окна с `BackHandler`-иерархией. Overlay переживает process death через `OverlaySaver`; месяц/дата/режим — в `SavedStateHandle`.

### 5.2 CalendarViewModel — состояние

| Flow | Источник | Sharing |
|---|---|---|
| `currentMonth`, `selectedDate`, `calendarMode` | `MutableStateFlow` + `SavedStateHandle` | — |
| `dayData` / `savedDayData` | `CalendarRepository` | `Eagerly` + peek как initial |
| `effectiveDayData` | combine(day, saved, mode) | `WhileSubscribed(5s)` |
| `currentMonthDayData`, `recentStudents` | производные текущего месяца | `WhileSubscribed` |
| `archiveMismatchDates` | `ArchiveSyncCompare` на `Default` | `WhileSubscribed` |
| `daysNeedingArchive` | `PastSessionsArchiveCollector` (окно 30 дней) | `WhileSubscribed` |
| `selectedDayContext` | дата + synced/archived/effective | `WhileSubscribed`, initial `null` |

Режимы: `SYNCED` (YClients, редактирование ограничено интенсивами через `mergeSyncedDayPreservingNonIntensives`) и `PERSONAL` (архив, полное редактирование, `updateSessionStatus`/`deleteSession`).

### 5.3 Формат строк сессий (грамматика SessionParser)

Календарь хранит день как `List<String>`; каждая строка — одна сессия:

```
Ученик:      name|attended               (legacy bool)
             name|status|time|phone|comment      status ∈ {0,1,2,3}
Интенсив:    __INTENSIVE__:[=]amount|name|status[|time[|children]]
             children: "имя|код|phone|comment" через ";;"  (= — фикс. сумма)
Диагностика: __DIAGNOSTICS__:amount|name|status|time
```

Парсер — `split('|')` без regex; `withStatus`/`SessionFormat.serialize*` обеспечивают round-trip. Слабое место грамматики: позиционные поля без экранирования `|` (находки U2, U7). Дедупликация «ученик ↔ ребёнок интенсива» — `buildIntensiveChildrenByTime` + нормализация имён.

### 5.4 Статистика и деньги

- **Месяц** (`computeMonthStats`): в gross — только `ARRIVED` (ученик × `pricePerSession`, диагностика × `pricePerDiagnostics` или сумма записи, интенсив `totalAmount(pricePerIntensiveChild, onlyArrived=true)`); `EXPECTED`/`CONFIRMED` → `expectedIncome`; `netProfit = max(0, gross − monthlyTaxAmount)`; `completedCount` — ученики + диагностики, интенсивы отдельным счётчиком.
- **Год** (`computeProfileYearStats`): 12 × месяц; `totalNetEarned = Σ netProfit`.
- **«На руки»**: `salaryInHand = max(0, netProfit − (advance + main | salaryOnCard))`.
- Ячейки сетки: `formatCalendarCounts`; интенсив — точка, не в счётчике.

### 5.5 Прочие ViewModel

| VM | Состояние | Хранилище |
|---|---|---|
| `ProfileViewModel` | `userProfile` (Eagerly) | очередь `MutableSharedFlow` → `updateProfile`; цена — debounce 600 мс → `applySessionPriceChange` |
| `AppSettingsViewModel` | тема, автосинк, master-тумблер уведомлений | DataStore / `neiro_sync_prefs` / notification prefs / `neiro_profit_display_prefs` |
| `SessionNotificationSettingsViewModel` | снимок prefs при init | notification prefs + перепланирование через координатор |
| `AuthViewModel` | login/password/error/loading | пароль только в памяти, чистится при уходе и в `onCleared` |
| `SyncViewModel` | состояние синка, `isLoggedIn`, avatar | mutex на UI-sync, logout через `LogoutCoordinator` |

---

## 6. Сборка, дистрибуция, тесты

### 6.1 Версии (июль 2026)

AGP 9.2.1, Gradle 9.4.1, Kotlin 2.2.10, compile/targetSdk 35, minSdk 24, Compose BOM 2024.10.00 (не бампается — by design), WorkManager 2.10.0, Retrofit 2.11 / OkHttp 4.12, Firebase BOM 33.7.0, security-crypto 1.1.0-alpha06 (by design). Версии — только через `gradle/libs.versions.toml`. Отстают: core-ktx 1.13.0, lifecycle 2.8.6, Firebase BOM (34.x — breaking, KTX удалены).

### 6.2 Build types

- `debug`: `.debug` suffix, `DEV_LOGIN`/`DEV_PASSWORD` из `local.properties`;
- `prerelease`: как release, подпись debug;
- `release`: minify + shrinkResources, R8 full mode; подпись только при полном наборе `RELEASE_*` в `local.properties` (иначе fail-fast). Keystore и `google-services.json` не в git (есть `.example`). Секреты (`YCLIENTS_PARTNER_TOKEN`, `NEIRO_PUSH_API_KEY`) → `BuildConfig` — зафиксированный компромисс. **Но в git закоммичен собранный `app/release/app-release.aab` с этими секретами внутри — находка B1 (критично).**

### 6.3 Манифест

Разрешения: `INTERNET`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` (+ merged: `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, FCM, `FOREGROUND_SERVICE` от WorkManager). Компоненты: `MainActivity` (exported, singleTop), `SessionNotificationBootReceiver` (not exported), `NeiroFirebaseMessagingService` (not exported). Cleartext запрещён (`network_security_config`). Backup: cloud — полный exclude; D2D — `datastore/` включён (by design).

### 6.4 Тесты

26 unit-файлов, ~132 `@Test`, только JUnit 4 (без MockK/Robolectric/coroutines-test/Compose UI tests). Хорошо покрыты: `SessionParser` (26), `YClientsCalendarSync` merge-логика (14), статистика (10+9+6+5), change detector (6), archive compare (6). **Не покрыты вовсе**: `TokenStorage`, `YClientsRepository`/клиент, `CalendarDataStore` (mutex/sync-кэш/import-export), все push-классы, координаторы WorkManager, `LogoutCoordinator`, все ViewModel, Compose UI. Единственный androidTest — заглушка, падающая на debug-сборке из-за суффикса пакета (находка B3).

CI отсутствует (`.github/workflows` нет).

---

## 7. Осознанные ограничения (наследуются из прошлых аудитов)

Полный список — [`docs/audit-17.07.26/OUT_OF_SCOPE.md`](../audit-17.07.26/OUT_OF_SCOPE.md). Ключевое, что **не** трогаем и не считаем находками: authoritative wipe текущего месяца; сброс правок в `DayDetailsDialog` при фоновом sync (`LaunchedEffect(initialNames)`); архивные операции из `cachedState`; отсутствие DI; god-composables; секреты в `BuildConfig` как таковые; хранение календаря JSON-блобом; hardcoded русские строки; персональные дефолты `UserProfile`; Compose BOM и security-crypto версии.
