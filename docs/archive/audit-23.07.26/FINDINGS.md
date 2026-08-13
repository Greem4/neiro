# Находки аудита 23.07.26

Полный реестр проблем по текущему коду ветки `аудит-3`. Номера строк соответствуют состоянию на дату аудита. Пути сокращены от `app/src/main/java/ru/greemlab/neiro/`.

Фиксы аудита 17.07.26 (волны A–E) проверены — **все на месте и работают по замыслу**; ниже только новые проблемы, регрессии вокруг старых фиксов и то, что прошлый аудит пропустил. Ограничения из [`docs/audit-17.07.26/OUT_OF_SCOPE.md`](../audit-17.07.26/OUT_OF_SCOPE.md) соблюдены — перечисленное там находками не считается.

Нумерация: `B` — сборка/безопасность, `S` — синхронизация/push, `N` — уведомления, `D` — данные/сеть, `U` — UI календаря/парсер, `P` — профиль/настройки/auth. Приоритетный план — в [ROADMAP.md](ROADMAP.md).

## Сводная таблица

| # | Проблема | Серьёзность |
|---|----------|-------------|
| B1 | Release AAB с запечёнными секретами закоммичен в git | **Критично** |
| S1 | `finally` оживляет keepalive/live-цепочки после cancel (logout); `runCatching` глотает `CancellationException` | **Критично** |
| N1 | Потеря напоминаний: окно ±7 мин уже периода 15 мин, нет catch-up | **Критично** |
| D1 | Гонка 401-logout: асинхронный полный logout затирает новую сессию | **Высоко** |
| S2 | `LiveApiRefreshWorker`: `Result.retry()` + `scheduleNext` раздувают цепочку | **Высоко** |
| S3 | При активном push нет foreground-опроса — UI устаревает при задержке FCM | **Высоко** |
| S4 | Инкрементальный sync оставляет «призраков» при переносе записи на другой день | **Высоко** |
| N2 | События и in-app лента теряются при выключенном POST_NOTIFICATIONS | **Высоко** |
| N3 | `rescheduleDigest`: cancel + `KEEP` — гонка; `finally` отменённого воркера ставит работу заново | **Высоко** |
| D2 | `applySessionPriceChange` читает `cachedState`, а не DataStore — может затереть профиль | **Высоко** |
| D3 | `snapshotsFlow` cold + side-effects без `shareIn`: N парсов, гонка со writers | **Высоко** |
| U1 | Перезапись архива при mismatch недостижима (мёртвая ветка) | **Высоко** |
| U2 | Интенсив без времени + дети ломает грамматику сериализации | **Высоко** |
| U3 | После process death DayDetails мгновенно закрывается (null-контекст) | **Высоко** |
| U4 | Stale closure `expanded` в свайпе замен/интенсивов | **Высоко** |
| P1 | «Сменить аккаунт»: logout асинхронный, форма входа не гарантирована | **Высоко** |
| P2 | `login()` без защиты от повторного сабмита | **Высоко** |
| P3 | Рассинхрон master-тумблера уведомлений между двумя ViewModel | **Высоко** |
| B2 | Baseline profile устарел (сигнатуры не совпадают с кодом) | **Высоко** |
| B3 | androidTest падает на debug-сборке (хардкод пакета) | **Высоко** |
| D4–D8, S5–S8, N4–N9, U5–U9, P4–P6, B4–B7 | Средние | Средне |
| D9–D10, S9, N10–N12, U10–U12, P7–P9, B8–B10 | Низкие | Низко |

---

## B — Сборка и безопасность

### B1. Критично — release AAB с секретами в git

**Файлы:** `app/release/app-release.aab` (6,1 МБ, tracked), источник секретов: `app/build.gradle.kts:64–67`, `push/PushClient.kt:21`.

AAB собран с заполненным `local.properties`: в dex лежат plaintext `NEIRO_PUSH_API_KEY` (общий Bearer для register/unregister устройств) и `YCLIENTS_PARTNER_TOKEN`. Бинарь и mapping — в истории git. Сам факт секретов в BuildConfig — зафиксированный компромисс; **коммит собранного артефакта — нет**: любой доступ к репозиторию = извлечение ключей `strings`-ом без jadx.

**Фикс:** удалить `app/release/**` из индекса; в `.gitignore` — `*.aab`, `*.apk`, `app/release/`; **ротировать `NEIRO_PUSH_API_KEY`** (и по возможности partner token); при желании — переписать историю (`git filter-repo`), иначе считать ключи скомпрометированными навсегда.

### B2. Высоко — baseline profile устарел

**Файл:** `app/src/main/baseline-prof.txt:37` (и сигнатура `CalendarScreenContent`, стр. 25).

Профиль ссылается на `CalendarViewModel.<init>(Application)`, а в коде конструктор `(Application, SavedStateHandle)` (`ui/calendar/CalendarViewModel.kt:47–50`). Правила не матчатся — AOT-компиляция горячих путей частично не работает, файл создаёт ложное чувство оптимизации.

**Фикс:** перегенерировать через macrobenchmark либо поправить сигнатуры; ручной файл «на глаз» не поддерживать.

### B3. Высоко — androidTest падает на debug

**Файл:** `app/src/androidTest/.../ExampleInstrumentedTest.kt:22`.

Ожидает пакет `ru.greemlab.neiro`, но debug имеет suffix `.debug`. Единственный instrumented-тест всегда красный.

**Фикс:** assert через `BuildConfig.APPLICATION_ID` / `targetContext.packageName`, либо удалить заглушку.

### B4. Средне — нет CI

`.github/workflows` отсутствует. Юнит-тесты (~132) никто автоматически не гоняет; нет защиты от коммита `google-services.json` / `*.aab`.

**Фикс:** минимальный workflow: `testDebugUnitTest` + проверка запрещённых артефактов в диффе.

### B5. Средне — `node_modules` в git

`scripts/icon/node_modules/**` — 140 файлов в индексе.

**Фикс:** `.gitignore` + `git rm -r --cached scripts/icon/node_modules`.

### B6. Средне — зависимости заметно отстают

`gradle/libs.versions.toml`: core-ktx 1.13.0, lifecycle 2.8.6, Firebase BOM 33.7.0, activity-compose 1.9.3 (Compose BOM — не трогаем by design). Firebase 33→34 — breaking (удалены KTX-артефакты), планировать отдельно.

**Фикс:** плановый bump core/lifecycle/activity/WorkManager; Firebase — отдельным заходом.

### B7. Средне — `localeFilters` содержит `en` без переводов

**Файл:** `app/build.gradle.kts:74`; ресурсы — только `values/` (русский).

**Фикс:** убрать `en` из фильтра (перевод строк — отдельно, OOS).

### B8. Низко — `enableBaselineProfile = false` в release

**Файл:** `app/build.gradle.kts:116–118`. Конфиг противоречит наличию `profileinstaller` + `baseline-prof.txt` (профиль в AAB всё равно попадает, но локальный `installRelease` его не ставит).

**Фикс:** включить или задокументировать намерение комментарием.

### B9. Низко — merged `FOREGROUND_SERVICE` без type

WorkManager приносит permission; приложение FGS не стартует — риска нет, пока не появится `setForegroundAsync`. Держать в голове для API 34+.

### B10. Низко — пробелы тестов в I/O-слое

Не покрыты: `TokenStorage`, `YClientsRepository`, `CalendarDataStore` (mutex/sync-кэш/import-export), push-классы, координаторы, `LogoutCoordinator`, ViewModel'ы. Минимум для регрессий этого аудита: тесты на round-trip интенсива с детьми без time (U2), диапазон reminder-окна (N1), merge переноса записи (S4).

---

## S — Синхронизация и push

### S1. Критично — `finally` оживляет цепочки после cancel; `runCatching` глотает cancellation

**Файлы:** `push/PushKeepAliveWorker.kt:25–33`, `sync/LiveApiRefreshWorker.kt:42–44`.

```kotlin
try {
    runCatching { PushRegistrar.registerNow(applicationContext) }
    runCatching { YClientsCalendarSync.get(applicationContext).refreshLiveRange() }
} finally {
    PushKeepAliveCoordinator.scheduleNext(applicationContext)
}
```

Два дефекта сразу:

1. `LogoutCoordinator.cancel*` отменяет RUNNING-воркер → корутина отменяется → `finally` **всё равно** ставит следующее звено. Logout снял цепочку, воркер тут же воскресил её; после выхода из аккаунта keepalive/live продолжают жить (следующий запуск отвалится только по `isLoggedIn` на старте, но цепочка уже снова в очереди и переустанавливается вечно).
2. `runCatching` перехватывает и `CancellationException` — cooperative cancellation сломан: отменённый воркер «успешно» завершает работу и возвращает `Result.success()`.

**Фикс:** в `finally` планировать только при `!isStopped` (+ для live: `!PushConfig.isActive`); `runCatching` заменить на `try/catch` с пробросом `CancellationException` (или `coroutineContext.ensureActive()` после `runCatching`).

### S2. Высоко — `Result.retry()` + `scheduleNext` раздувают очередь live-цепочки

**Файл:** `sync/LiveApiRefreshWorker.kt:31–44`.

При Failure воркер возвращает `retry()` **и** в `finally` `APPEND_OR_REPLACE`-ит ещё одно отложенное звено. Каждый неудачный запуск добавляет узел в unique-цепочку: при стабильной ошибке (без сети в push-off сборке) очередь монотонно растёт.

**Фикс:** развести пути: `retry()` **без** schedule; `scheduleNext` — только при успехе (и `!isStopped`), как у keepalive.

### S3. Высоко — при активном push нет foreground-опроса

**Файл:** `sync/LiveApiCoordinator.kt:52–61, 114–115`.

`serverPushActive` отключает и фоновую цепочку, и **foreground-поллинг**. Пока приложение открыто, данные обновляются только: разово на `onStart`, по FCM и keepalive'ом раз в 30–60 мин. При задержке/потере FCM открытый экран календаря устаревает сильнее, чем в сборке без push.

**Фикс:** при активном push оставить foreground-поллинг (5 мин, только пока `ON_START`); keepalive остаётся фоновым backup'ом.

### S4. Высоко — «призраки» при переносе записи на другой день

**Файл:** `sync/YClientsCalendarSync.kt:460–485`.

`changed_after` отдаёт запись с **новой** датой; refetch текущего месяца идёт только по окну `min..max` изменённых дат. Старый день записи в окно не попадает и не чистится — в календаре остаётся «призрак» до полного live-sync (раз в 6 ч) или суточного авто.

**Фикс:** варианты по цене трафика: (а) при инкременте с записями текущего месяца refetch всего текущего месяца; (б) по `id` записи удалять локальные вхождения этого id вне новых дат; (в) сократить интервал полного live-sync. Рекомендуется (б) — точечно и без лишнего трафика.

### S5. Средне — logout не отменяет `push_fcm_sync`

**Файлы:** `auth/LogoutCoordinator.kt:28–30` vs `push/PushSyncCoordinator.kt:13`.

Отменяются live + keepalive; in-flight FCM-sync может уйти в `retry()` и гоняться с очисткой состояния.

**Фикс:** `cancelUniqueWork("push_fcm_sync")` в LogoutCoordinator; имя — в общий const.

### S6. Средне — keepalive глотает ошибки без retry

**Файл:** `push/PushKeepAliveWorker.kt:25–33`.

`runCatching` + всегда `Result.success()`: transient-ошибка register/sync не ретраится WorkManager'ом — следующий шанс через 30–60 мин.

**Фикс:** при `SyncOutcome.Failure` / register=false — `Result.retry()` без `scheduleNext` (делать вместе с S1).

### S7. Средне — unregister молча игнорирует ошибки

**Файл:** `push/PushRegistrar.kt:129–136`.

`runCatching` без проверки HTTP-кода: при сетевой ошибке устройство остаётся зарегистрированным на сервере и продолжает получать push для разлогиненного аккаунта.

**Фикс:** логировать; при неудаче — флаг «pending unregister» и повтор на следующем старте.

### S8. Средне — рассинхрон проверок `PushConfig`

**Файл:** `push/PushSyncCoordinator.kt:16` (`isServerConfigured`) при том, что остальной код проверяет `isActive`.

**Фикс:** единообразно `PushConfig.isActive` (для unregister оставить `isServerConfigured`).

### S9. Низко — дубль имени live-work и устаревший KDoc

`auth/LogoutCoordinator.kt:44` дублирует строку unique-имени из `sync/LiveApiCoordinator.kt:36`; комментарий «22:00–08:00» при фактическом окне 21:00–09:00 (`LiveApiCoordinator.kt:30`).

**Фикс:** общий const; поправить KDoc.

---

## N — Уведомления

### N1. Критично — потеря напоминаний: окно ±7 мин уже периода 15 мин

**Файлы:** `notifications/UpcomingSession.kt:87–96` (`REMINDER_WINDOW_HALF = 7`), `notifications/SessionNotificationCoordinator.kt:550–553`.

Основной путь — one-time воркер; fallback — periodic раз в 15 мин с окном совпадения шириной 14 мин (±7) — periodic может «перешагнуть» окно. Плюс после каждого изменившего календарь sync все reminder-воркеры пересоздаются (`cancelAllWorkByTag`), и если расчётное время уже прошло (`delayMs <= 0`) — one-time не ставится вовсе. Сессия ещё в будущем, `wasReminderNotified == false`, но напоминание уже никогда не придёт.

**Фикс:** (1) `REMINDER_WINDOW_HALF ≥ 8` (лучше 10); (2) catch-up: если `0 ≤ minutesUntilStart ≤ reminderMinutesBefore` и не notified — показать немедленно; (3) при `delayMs <= 0`, но сессия в будущем — enqueue с delay 0.

### N2. Высоко — события и in-app лента теряются при выключенном POST_NOTIFICATIONS

**Файл:** `notifications/SessionNotificationCoordinator.kt:210–214`.

При `!areNotificationsEnabled()` не вызывается ни `showEvents` (system push), ни `InAppNotificationRecorder` (лента внутри приложения), ни `markEventNotified`. Diff — one-shot: следующий sync с тем же календарём события не повторит. Пользователь без разрешения на уведомления теряет и внутриприложенческую историю изменений.

**Фикс:** in-app запись и `mark*` делать всегда; system-notify — отдельно, по разрешению.

### N3. Высоко — `rescheduleDigest`: cancel + `KEEP` гонка; `finally` отменённого воркера

**Файлы:** `notifications/SessionNotificationCoordinator.kt:277–302`, `notifications/SessionScheduledDigestWorker.kt:21–25`.

`cancelUniqueWork` асинхронен: следующий сразу за ним `enqueue(KEEP)` может быть отброшен, пока старый ещё числится ENQUEUED — пользовательская смена времени сводки молча не применяется. Вторая грань: у `CoroutineWorker` `finally` выполняется и при cancel → отменённый воркер `APPEND_OR_REPLACE`-ит своё перепланирование **поверх** только что заданного пользователем.

**Фикс:** UI-путь — один `enqueue` с `ExistingWorkPolicy.REPLACE` (без отдельного cancel); в `rescheduleDigestFromWorker` — не перепланировать при `isStopped`.

### N4. Средне — periodic `UPDATE` на каждый холодный старт

**Файл:** `notifications/SessionNotificationCoordinator.kt:493–500`.

`scheduleDailyNotifications` из `initialize()` (Application + Boot) всегда с `ExistingPeriodicWorkPolicy.UPDATE` — сбрасывает фазу 15-минутного тика, ослабляя и без того хрупкий fallback напоминаний (усиливает N1).

**Фикс:** `KEEP` при init; `UPDATE` только при смене настроек.

### N5. Средне — `slotKey` без типа сессии

**Файлы:** `notifications/SessionSlotKey.kt:16–17`, `notifications/SessionChangeDetector.kt:14–18`.

Ключ = `имя|дата|время`. Урок и диагностика одного клиента в одно время схлопываются в `associateBy` — детектор теряет/путает события.

**Фикс:** включить `kind` в slotKey (проверить обратную совместимость сохранённых dedupe-ключей: старые `was*Notified` перестанут матчиться — допустимо, но осознанно).

### N6. Средне — утренняя сводка «сегодня» скрывает уже начавшиеся занятия

**Файл:** `notifications/UpcomingSession.kt` (`collect` отбрасывает `startTime <= now`).

Если digest доставлен с опозданием (Doze), прошедшие/идущие слоты дня в сводку не попадают.

**Фикс:** для сводки «сегодня» — отдельная выборка всех слотов дня (или «оставшиеся + идущие»).

### N7. Средне — logout не чистит archive-claims

**Файл:** `notifications/SessionNotificationCoordinator.kt` (`onLoggedOut`) — сбрасывает baseline/dedupe/today/tomorrow, но не `archive_reminder_epoch_days_v2`.

**Фикс:** чистить архивный LRU вместе с остальным состоянием.

### N8. Средне — события/напоминания без rollback при неудачном показе

**Файл:** `notifications/SessionNotificationDisplay.kt` (`showEvents`/`showReminder` — `Unit`).

У дайджестов claim откатывается при неудаче (фикс E3 прошлого аудита), у событий/напоминаний `mark*Notified` выполняется даже если все `notify()` упали с `SecurityException`.

**Фикс:** по образцу дайджестов — возвращать успех per-item, mark только показанные.

### N9. Средне — thrash напоминаний на каждый изменивший sync

**Файл:** `notifications/SessionNotificationCoordinator.kt` (`applyReminderSchedule` → `cancelAllWorkByTag` + полный re-enqueue).

Live-poll до раза в 5 минут пересоздаёт все one-time reminder'ы. В сочетании с `delayMs <= 0`-пропуском — главный усилитель N1.

**Фикс:** diff-перепланирование: трогать только изменившиеся dedupe-ключи.

### N10. Низко — `calendar_snapshot` пишется, но не читается

`saveSnapshot` вызывается, `loadSnapshot()` мёртв (diff считается по before/after в памяти). Раздувание prefs на размер календаря.

**Фикс:** удалить снапшот и запись.

### N11. Низко — ложный `RESCHEDULED` при совпадении нормализованных имён разных клиентов.

### N12. Низко — ранний `Result.failure()` в digest-воркере до `try/finally` (битый input не перепланирует цепочку; кейс почти невозможный).

---

## D — Данные и сеть

### D1. Высоко — гонка 401-logout с повторным логином

**Файлы:** `data/network/YClientsRepository.kt:233–248`, `auth/LogoutCoordinator.kt:25–37`.

На 401 сразу `tokenStorage.clear()`, затем **асинхронно** `LogoutCoordinator.logout()` (сетевой unregister → в конце повторный `repository.logout()` + `clearSyncState`). Пока unregister идёт, пользователь может успеть войти заново — хвост logout'а затрёт **новую** сессию (токены, watermarks) и снимет только что поднятые воркеры.

**Фикс:** зафиксировать «поколение» сессии при входе в `handleUnauthorized` (например, `userToken` на момент 401) и в конце logout чистить состояние только если поколение не сменилось; либо блокировать `login()` пока `logoutOn401InProgress`.

### D2. Высоко — `applySessionPriceChange` читает `cachedState` вместо DataStore

**Файл:** `data/CalendarDataStore.kt:221–235`.

В отличие от `updateProfile` (RMW внутри `dataStore.edit`), профиль берётся из `cachedState` — потенциально устаревший (до `warmUp` — из sync-кэша). `copy(pricePerSession=…)` затем перезаписывает **все** поля профиля устаревшим снимком.

**Фикс:** читать и трансформировать профиль внутри `dataStore.edit { }` по образцу `updateProfile`.

### D3. Высоко — `snapshotsFlow`: cold flow с side-effect'ами без `shareIn`

**Файл:** `data/CalendarDataStore.kt:131–162`.

Четыре публичных flow — независимые подписки на DataStore: N коллекторов = N Gson-парсов всего календаря + N `writeSyncCache` на каждую эмиссию. Побочная запись `cachedState`/sync-кэша происходит **вне** `writeMutex` — запоздалая эмиссия после серии edit может откатить peek/sync-кэш к более старому снимку.

**Фикс:** `snapshotsFlow.shareIn(appScope, Eagerly, replay = 1)`; side-effect'ы — с generation-счётчиком (игнорировать stale) либо только из writers.

### D4. Средне — zombie-архив из sync-кэша после `clearAllData`

**Файл:** `data/CalendarDataStore.kt:281–292, 351–361`.

`writeSyncCache`/`clearAllData` ключ `saved_day_data_json` не трогают, а `loadFromSyncCache` его читает: на старых установках после сброса данных холодный старт кратко показывает старый архив.

**Фикс:** не читать архив из sync-кэша или чистить ключ в `clearAllData`.

### D5. Средне — `login` не сбрасывает `staffId`

**Файлы:** `data/network/YClientsRepository.kt:62–76`, `data/network/TokenStorage.kt:100–111`.

Повторный вход другим сотрудником той же компании без полного logout оставляет старый `staffId` — показываются чужие/пустые записи до ручного повторного detect.

**Фикс:** при успешном login обнулять `staffId` и сразу вызывать `detectAndSaveStaffId()`.

### D6. Средне — `restoreAllData`: частичный импорт

**Файл:** `data/CalendarDataStore.kt:331–341`.

`ArchiveNotificationStore.importJson` выполняется до записи DataStore; сбой `edit` оставляет уведомления импортированными, календарь — нет.

**Фикс:** сначала DataStore, затем notifications; при ошибке — компенсация.

### D7. Средне — инициализация TokenStorage/KeyStore на main

**Файл:** `data/network/TokenStorage.kt:120–157`.

Создание `MasterKey` + `EncryptedSharedPreferences` (disk + KeyStore I/O, плюс путь пересоздания битого keyset) происходит при первом обращении — часто на main-потоке из Application/координаторов.

**Фикс:** прогрев на `Dispatchers.IO` при старте приложения до первого UI-обращения.

### D8. Средне — `RetryInterceptor` блокирует поток и игнорирует `Retry-After`

**Файл:** `data/network/YClientsClient.kt:98–123`.

Backoff через `Thread.sleep` на потоке OkHttp: пачка 5xx/429 занимает connection pool; 429 без учёта `Retry-After`; ретраятся и POST.

**Фикс:** уважать `Retry-After`; не ретраить неидемпотентные запросы; долгосрочно — retry на уровне репозитория с `delay()`.

### D9. Низко — мёртвый код и мелочи

`YClientsApi.getRecord` не используется (`YClientsApi.kt:70–74`); неиспользуемый импорт `Header`; `snapshotState()` под `@Suppress("unused")`; `parseErrorMessage` создаёт `Gson()` на каждый вызов (`YClientsRepository.kt:401–402`).

### D10. Низко — `exportAllData` без `writeMutex` (`CalendarDataStore.kt:303–311`) — возможен torn read mid-write.

---

## U — UI календаря и парсер

### U1. Высоко — перезапись архива при mismatch недостижима

**Файл:** `ui/components/DayDetailsDialog.kt:435–441`.

```kotlin
when {
    allowStatusEdit && isArchived -> onUnarchive()
    isArchived && archiveMismatch -> onRequestOverwriteArchive()
    ...
}
```

`allowStatusEdit` с экрана всегда `true`, поэтому первая ветка перехватывает все архивные дни — до `onRequestOverwriteArchive()` управление не доходит. Диалог перезаписи архива в `CalendarScreen` мёртв, функция «обновить архив из синка» недоступна.

**Фикс:** проверять `isArchived && archiveMismatch` первой веткой.

### U2. Высоко — интенсив без времени + дети ломает грамматику

**Файлы:** `ui/calendar/SessionParser.kt:493–499` (сериализация) vs `:362–368` (парс `split("|", limit = 5)`); источник пустого time — `sync/YClientsCalendarSync.kt:791`.

При `time.isBlank()` поле времени в строку не пишется, но дети дописываются следом: `__INTENSIVE__:amount|name|status|children...`. Парсер позиционный — читает имя первого ребёнка как `time`, остальных детей калечит. Любое последующее редактирование/`withStatus` фиксирует порчу в DataStore.

**Фикс:** при непустых `children` всегда сериализовать слот времени (пустой допустим): `$base|$time|$childrenPart`; добавить round-trip тест «интенсив без времени с детьми».

### U3. Высоко — после process death DayDetails закрывается

**Файлы:** `ui/screens/CalendarScreen.kt:409–412`, `ui/calendar/CalendarViewModel.kt:223–226`.

Overlay восстанавливается (`OverlaySaver`), но `selectedDayContext` стартует с `initialValue = null` → `LaunchedEffect` первого кадра сбрасывает overlay в `None`. Фикс C4 прошлого аудита (восстановление состояния) фактически аннулируется для открытого дня.

**Фикс:** не закрывать при `selectedDate != null` (контекст ещё грузится); либо initial из peek по восстановленной дате.

### U4. Высоко — stale closure в свайпе замен/интенсивов

**Файл:** `ui/components/daydetails/ScheduleSlotItem.kt:308–327` (и аналог в IntensiveCover ~520).

`pointerInput(removed.size)` кэширует `expanded`/`onToggle` на момент композиции: после раскрытия жест «свернуть» вызывает `onToggle` со старым значением — состояние и анимация расходятся, слот «отпрыгивает» обратно.

**Фикс:** `rememberUpdatedState(expanded)` / `rememberUpdatedState(onToggle)` внутри жеста, либо добавить `expanded` в ключи `pointerInput`.

### U5. Средне — «Итог» дня завышает интенсив

**Файл:** `ui/components/DayDetailsDialog.kt:847–850, 903–911`.

В строку статистики дня интенсив идёт с `totalAmount(..., onlyArrived = false)`, тогда как месяц/сводка считают `onlyArrived = true` — шапка дня расходится с месячной статистикой.

**Фикс:** `onlyArrived = true` для денежной части.

### U6. Средне — `describeDiff` молчит при расхождении только `amountFixed`

**Файл:** `ui/calendar/ArchiveSyncCompare.kt:66–77 vs 198–204`.

`differs()` учитывает флаг `=` в канонической форме, а `fieldDiffLines` сравнивает только числовую сумму — баннер mismatch без деталей и без клика.

**Фикс:** явно диффать `amountFixed`.

### U7. Средне — `|` в комментарии ученика обрезается

**Файл:** `ui/calendar/SessionParser.kt:315`.

Парс расширенного формата ученика — `split('|')` без `limit`, сериализация пишет comment последним полем: комментарий с `|` теряет хвост при round-trip.

**Фикс:** `split("|", limit = 5)`.

### U8. Средне — flash неверного месяца/режима после восстановления

**Файл:** `ui/calendar/CalendarViewModel.kt:115–135`.

`initialValue` для effective-данных/месяца — всегда peek **synced** + `YearMonth.now()`, игнорируя восстановленные PERSONAL-режим и месяц до первой эмиссии.

**Фикс:** строить initial по восстановленным mode/month из `SavedStateHandle`.

### U9. Средне — двойной парс в `computeDayStats`

**Файл:** `ui/components/DaySummaryStats.kt:37–41` — строки парсятся `SessionParser`-ом дважды.

**Фикс:** итерировать уже распарсенный список.

### U10. Низко — a11y: ячейки дня без `contentDescription` (`ui/components/DayCard.kt:64–69`).

### U11. Низко — `remember { LocalDate.now() }` в `StatsRow`/MonthPicker не обновляется после полуночи при открытом экране.

### U12. Низко — pull-to-refresh в DayDetails мёртв при открытии с календаря (не прокинут `onRefresh`).

---

## P — Профиль, настройки, авторизация

### P1. Высоко — «Сменить аккаунт»: форма входа не гарантирована

**Файлы:** `ui/profile/SettingsScreen.kt:396–401`, `ui/sync/SyncViewModel.kt:68–72`.

`onLogout()` запускает корутину и **сразу** открывается `AuthScreen`; пока `isLoggedIn` ещё `true`, показывается `LoggedInContent` вместо формы. Плюс пересечение с D1 (хвост logout затирает новый вход).

**Фикс:** открывать auth-оверлей по завершении logout (suspend/callback) либо в Auth форсировать форму при флаге `changeAccount`.

### P2. Высоко — `login()` без защиты от повторного сабмита

**Файл:** `ui/auth/AuthViewModel.kt:109–155`.

Нет проверки `isLoading` до `launch`: IME Done + кнопка = два параллельных `repository.login` (двойная регистрация push, гонка записи токенов).

**Фикс:** `if (_uiState.value.isLoading) return` в начале.

### P3. Высоко — рассинхрон master-тумблера уведомлений

**Файлы:** `ui/settings/AppSettingsViewModel.kt:48–62`, `ui/settings/SessionNotificationSettingsViewModel.kt:41–46`.

Два VM независимо кэшируют `prefs.isEnabled` в своих `StateFlow`. Смена тумблера на детальном экране не попадает в `AppSettingsViewModel` (Activity-scoped, жив в фоне) — по возврату экран показывает старое значение.

**Фикс:** единый источник (Flow поверх prefs через `OnSharedPreferenceChangeListener` или общий синглтон-StateFlow).

### P4. Средне — интенсивы в деньгах года, но не в «занятиях»

**Файлы:** `ui/calendar/CalendarStatsCalculator.kt:115–124`, `ui/profile/ProfileYearStatsSection.kt:186–188`.

Годовая сводка drawer'а: «N занятий · сумма» — сумма включает интенсивы, счётчик нет; месяц только с интенсивом выглядит как «0 занятий, но деньги есть».

**Фикс:** показывать `completedCount + completedIntensives` (или подпись «без интенсивов»).

### P5. Средне — налог в «Финансах» всегда полный, даже при `net = 0`

**Файлы:** `ui/calendar/CalendarStatsCalculator.kt:177–191`, `ui/components/CalendarDialogs.kt:181–187`.

При gross 500 и налоге 1000 строка показывает «Налог −1000», хотя удержано ≤ gross.

**Фикс:** отображать `min(monthlyTaxAmount, grossEarned)`.

### P6. Средне — регистрация через TopBar без вида деятельности

**Файл:** `ui/profile/SettingsScreen.kt:65–69` vs кнопка `:324`.

«Назад» завершает регистрацию при одном лишь имени; кнопка требует ещё `activityType` — два пути с разными условиями.

**Фикс:** уравнять условия.

### P7. Низко — `updatePrice` идёт в обход очереди обновлений профиля (`ui/profile/ProfileViewModel.kt:77–87`); после D2 логичнее `enqueueUpdate { copy(pricePerSession = …) }`.

### P8. Низко — мёртвый API автосинка в `SyncViewModel` (`:58–64`) — не используется UI, риск рассинхрона.

### P9. Низко — `totalTaxAmount = tax × 12` в годовой модели независимо от пустых месяцев; в UI сейчас не показывается — мина на будущее (`ui/profile/ProfileYearStats.kt:109`).
