# Аудит приложения Neiro — дорожная карта (июль 2026)

Полный аудит кодовой базы `app/` по четырём направлениям: **сеть/синхронизация**, **память/Compose UI**, **фоновые задачи/уведомления**, **хранилище/безопасность/сборка**. Каждая находка проверена по актуальному коду — номера строк соответствуют состоянию на дату аудита.

Документ — продолжение пакета `docs/refactoring-old/` (этапы 1–6). Сначала — сверка старого плана, затем новые находки, сгруппированные в волны A–E по приоритету.

## Правила работы (те же, что в docs/refactoring-old/)

- **Не запускать Gradle** — сборку проверяет пользователь, агент проверяет `ReadLints`.
- Коммиты: одна строка на русском, ≤72 символа, прошедшее время, без префиксов.
- **Не трогать ничего из `OUT_OF_SCOPE.md` (в этой папке)** — архив, `LaunchedEffect(initialNames)` в DayDetailsDialog, authoritative wipe текущего месяца и т.д.
- Один пункт = один коммит. Заготовки сообщений даны в конце каждого пункта.

---

## Статус старого плана (docs/refactoring-old/README.md)

Проверено по коду: **ETAP_1, 2, 3, 5, 6 фактически выполнены**, ETAP_4 выполнен почти весь. Чекбоксы acceptance criteria в `docs/refactoring-old/README.md` устарели — в коде уже сделаны:

- `recordLivePoll`/`recordFullLiveSync` после merge + save ✔
- `ExistingWorkPolicy.KEEP` для unique sync/keepalive workers ✔ (но см. **A1** — выбор KEEP для self-reschedule оказался ошибкой)
- `LiveApiRefreshWorker` try/finally ✔
- WebView destroy в `DisposableEffect.onDispose` ✔ (`YClientsWebView.kt:149–163`)
- Sync VM mutex ✔ (`SyncViewModel.kt:206–241`)

Из ETAP_4 осталось только 4.9.2 — нереактивный state в `AppSettingsScreen` (см. **C6**).

---

## Сводная таблица приоритетов

| # | Проблема | Серьёзность | Волна |
|---|----------|-------------|-------|
| A1 | `KEEP` из running worker обрывает цепочку фоновых опросов LiveApi | **Критично** | A |
| A2 | `PushKeepAliveWorker`: нет try/finally + тот же `KEEP` — keepalive умирает | **Критично** | A |
| A3 | Digest-уведомления отменяются при каждом sync (`cancel` + enqueue) | **Высоко** | A |
| A4 | `PushSyncWorker` не ретраит `SyncOutcome.Failure` | Средне | A |
| A5 | FCM-sync worker без network constraint | Средне | A |
| A6 | `SessionScheduledDigestWorker` без try/finally | Средне | A |
| B1 | Гонка: sync и UI перезаписывают весь `dayData` (full-map replace) | **Высоко** | B |
| B2 | `catch (Exception)` глотает `CancellationException` | **Высоко** | B |
| B3 | 401 чистит токены, но не делает полный logout | **Высоко** | B |
| B4 | N+1: полный повторный fetch текущего месяца при incremental sync | Средне | B |
| B5 | Ложное «обрезание» пагинации при ровно 10 000 записей | Средне | B |
| B6 | `detectAndSaveStaffId` молча глотает ошибки, без 401 | Средне | B |
| B7 | Нет retry/backoff на 408/429/5xx | Средне | B |
| B8 | Gson: non-null поля моделей + `setLenient()` | Средне | B |
| B9 | `PushRegistrar.onLogout` fire-and-forget | Средне | B |
| C1 | Gson-парс всего календаря + запись sync-кэша на Main-потоке | **Высоко** | C |
| C2 | `ProfileContent` в закрытом drawer пересчитывает годовую статистику | **Высоко** | C |
| C3 | `CalendarScreen` подписан на полные карты → рекомпоз всего дерева | **Высоко** | C |
| C4 | Состояние календаря не переживает process death | Средне | C |
| C5 | `collectAsState` без учёта lifecycle | Средне | C |
| C6 | `AppSettingsScreen`: одноразовое чтение prefs вместо StateFlow | Средне | C |
| C7 | Drag слота: чтение `Animatable` в composition — рекомпоз на каждый кадр | Средне | C |
| D1 | `EncryptedSharedPreferences`: fallback в plaintext + deprecated API | **Высоко** | D |
| D2 | DataStore без `corruptionHandler` | Средне | D |
| D3 | `warmUp`/RMW из `cachedState` вне `dataStore.edit` | Средне | D |
| D4 | Хардкод `companyId = 520135` в мёртвом `YClientsWebViewScreen` | Средне | D |
| D5 | Лог сырого `errorBody` API | Средне | D |
| D6 | Секреты в `BuildConfig` попадают в release APK | Высоко* | D |
| D7 | YClients-токены уходят на push-backend | Высоко* | D |
| E1–E9 | Мелочи: notification id, group summary, пароль в VM, callTimeout и др. | Низко | E |

\* D6/D7 — архитектурные компромиссы, зафиксированные в `OUT_OF_SCOPE.md`; требуют backend-работ, в коде приложения сейчас чинится только частично.

---

## Волна A — стабильность фоновых задач (критично, делать первой)

Эти баги приводят к тому, что **фоновое обновление календаря и уведомления молча перестают работать**.

### A1. Критично — `KEEP` из running worker обрывает цепочку LiveApi

**Файлы:** `sync/LiveApiRefreshWorker.kt:42–44`, `sync/LiveApiCoordinator.kt:113–133`

```kotlin
} finally {
    LiveApiCoordinator.scheduleNextBackgroundRefresh(applicationContext)
}
// ...
WorkManager.enqueueUniqueWork(BACKGROUND_WORK_NAME, ExistingWorkPolicy.KEEP, request)
```

`scheduleNextBackgroundRefresh` вызывается из `finally`, когда worker ещё в статусе `RUNNING`. Для WorkManager это «незавершённая работа с тем же unique-именем», и политика `KEEP` **молча отбрасывает** новый запрос. Цепочка фоновых опросов умирает после первого же запуска — без FCM (`PushConfig.isActive == false`) календарь в фоне не обновляется до перезапуска приложения.

**Фикс:** для self-reschedule использовать `ExistingWorkPolicy.APPEND_OR_REPLACE` (или отдельное unique-имя для «следующего» запуска). `KEEP` оставить только для внешних входов (login, FCM), чтобы не убивать mid-flight merge.

Коммит: `Исправил обрыв цепочки фонового опроса LiveApi`

### A2. Критично — keepalive: нет try/finally и тот же `KEEP`

**Файлы:** `push/PushKeepAliveWorker.kt:19–32`, `push/PushKeepAliveCoordinator.kt:31–35`

Две проблемы сразу:

1. `PushRegistrar.registerNow` не обёрнут в `runCatching`, а `PushKeepAliveCoordinator.scheduleNext` не в `finally` — исключение при регистрации обрывает keepalive насовсем.
2. Даже happy path: `scheduleNext` → `schedule` → `enqueueUniqueWork(..., KEEP, ...)` изнутри `RUNNING` worker'а — запрос отбрасывается, как в A1.

При активном push фоновый LiveApi-poll отключён (`LiveApiCoordinator.kt:114` — early return), то есть keepalive — единственная страховка от пропущенных FCM. С этими багами её нет.

**Фикс:** `try/finally { PushKeepAliveCoordinator.scheduleNext(...) }` + `APPEND_OR_REPLACE` внутри `scheduleNext`.

Коммит: `Исправил обрыв цепочки push keepalive`

### A3. Высоко — digest-уведомления отменяются при каждом sync

**Файл:** `notifications/SessionNotificationCoordinator.kt:218` (`scheduleAfterBaseline` → `rescheduleAllDailyDigests`), `:271–305` (`rescheduleDigest` — безусловный `cancelDigestWork` перед enqueue)

После каждого изменившего календарь sync/FCM вызывается `rescheduleAllDailyDigests` → `rescheduleDigest` → **всегда `cancelUniqueWork`** → enqueue. `KEEP` внутри `enqueueDigestWork` бесполезен: работа уже отменена. Если sync пришёл около времени сводки, он может убить уже запущенный digest-worker; если тот успел сделать `claim*`, сводка за день теряется безвозвратно (claim не откатывается).

**Фикс:** развести два пути — `ensureDigestsScheduled` (только `enqueueDigestWork` с `KEEP`, вызывается из sync) и `rescheduleDigest` (`cancel` + enqueue, вызывается только при смене времени/настроек пользователем).

Коммит: `Убрал сброс digest-уведомлений при каждом sync`

### A4. Средне — `PushSyncWorker` не ретраит `SyncOutcome.Failure`

**Файл:** `push/PushSyncWorker.kt:20–23`

`refreshLiveRange()` вернул `SyncOutcome.Failure` (ошибка API/сети без exception) → worker возвращает `Result.success()`. FCM-пуш «обработан», данных нет, следующий шанс — keepalive через 30–60 минут.

**Фикс:** как в `LiveApiRefreshWorker` — проверять outcome, при `Failure` возвращать `Result.retry()`.

Коммит: `Добавил retry в PushSyncWorker при неудачном sync`

### A5. Средне — FCM-sync без network constraint

**Файл:** `push/PushSyncCoordinator.kt:13–24`

У `LiveApiRefreshWorker` и keepalive constraint `NetworkType.CONNECTED` есть, у `PushSyncWorker` — нет. Без сети worker сразу стартует, падает и уходит в backoff-петлю.

**Фикс:** добавить `Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)`.

Коммит: `Добавил network constraint для FCM-sync worker`

### A6. Средне — `SessionScheduledDigestWorker` без гарантии reschedule

**Файл:** `notifications/SessionScheduledDigestWorker.kt:16–23`

Если `deliverScheduledDigest` бросит исключение, `rescheduleDigest` не вызовется — one-time сводка на завтра не запланируется до следующего sync.

**Фикс:** `try/finally { rescheduleDigest(...) }`.

Коммит: `Гарантировал перепланирование digest при ошибке`

### A7. Средне — два periodic worker'а каждые 15 минут

**Файл:** `notifications/SessionNotificationCoordinator.kt:445–473`

`SessionReminderWorker` + `SessionDailyNotificationWorker` — два отдельных unique periodic по 15 минут (минимум WorkManager), без constraints. One-time digests уже покрывают точное время; двойной periodic — лишние пробуждения и расход батареи.

**Фикс:** объединить в один periodic «notification tick» либо оставить один как fallback.

Коммит: `Объединил periodic-воркеры уведомлений в один`

### A8. Средне — BootReceiver: синхронная работа на main без `goAsync`

**Файл:** `notifications/SessionNotificationBootReceiver.kt:11–15`

`SessionNotificationCoordinator.initialize` делает prefs-чтение + серию `enqueue*` синхронно в `onReceive` на main-потоке после загрузки устройства.

**Фикс:** `goAsync()` + корутина, либо сразу ставить `OneTimeWorkRequest` «boot reschedule».

Коммит: `Перенёс работу BootReceiver с main-потока`

---

## Волна B — целостность данных и сеть

### B1. Высоко — гонка: sync и UI перезаписывают весь `dayData`

**Файлы:** `sync/YClientsCalendarSync.kt:301–423`, `ui/calendar/CalendarViewModel.kt:220–228`, `data/CalendarDataStore.kt:214–220`

Паттерн везде одинаковый: прочитать полную карту (`dayDataFlow.first()` / `dayData.value`) → изменить → `saveDayData(полная карта)`. `writeMutex` сериализует только запись, а не read-modify-write целиком. Если пользователь редактирует день, пока идёт sync (или наоборот), последний писатель затирает чужие изменения целиком.

**Фикс:** добавить в репозиторий `suspend fun updateDayData(transform: (Map<LocalDate, List<String>>) -> Map<...>)`, где чтение текущего значения и запись происходят под одним `writeMutex` (по образцу `updateProfile`). Все вызовы `saveDayData(snapshot + правка)` перевести на него.

Коммит: `Сделал обновление dayData атомарным`

### B2. Высоко — `catch (Exception)` глотает `CancellationException`

**Файл:** `data/network/YClientsRepository.kt:75–77, 190–192, 253–255, 305–307`

Отмена корутины (logout, уход с экрана, отмена worker'а) превращается в `ApiResult.Error("Ошибка сети...")` вместо проброса — structured concurrency ломается, отменённые операции «завершаются успешно» с ошибкой.

**Фикс:** во всех четырёх местах перед `catch (e: Exception)` добавить:

```kotlin
} catch (e: CancellationException) {
    throw e
}
```

Коммит: `Перестал глотать CancellationException в репозитории`

### B3. Высоко — 401 не делает полный logout

**Файл:** `data/network/YClientsRepository.kt:195–198`

`handleUnauthorized` только чистит токены. Push-устройство остаётся зарегистрированным на сервере со старым `user_token`, watermarks не сброшены, keepalive гаснет лишь косвенно. Механизм `LogoutCoordinator` уже есть (ETAP_2), но 401-путь его не использует.

**Фикс:** из `handleUnauthorized` дёргать `LogoutCoordinator.logout` (через application scope/callback), минимум — push unregister + `clearSyncState` + cancel workers.

Коммит: `Подключил LogoutCoordinator к обработке 401`

### B4. Средне — N+1: повторный полный fetch текущего месяца

**Файл:** `sync/YClientsCalendarSync.kt:438–456`

Incremental sync уже получил changed-записи; если среди них есть записи текущего месяца, следом идёт второй (часто многостраничный) `getRecords` за весь месяц.

**Фикс:** переиспользовать уже полученные данные либо сразу делать один full-month fetch без предварительного incremental для текущего месяца.

Коммит: `Убрал дублирующий запрос записей текущего месяца`

### B5. Средне — ложное «обрезание» пагинации

**Файл:** `data/network/YClientsRepository.kt:147–187, 267–301`

При ровно `MAX_PAGES × PAGE_SIZE` (10 000) записей возвращается ошибка «загрузка обрезана», хотя всё загружено. `meta.total_count` из ответа не используется.

**Фикс:** опираться на `RecordsMeta.totalCount` / `ClientsMeta.totalCount` для определения конца.

Коммит: `Исправил ложное обрезание пагинации по total_count`

### B6. Средне — `detectAndSaveStaffId` молча глотает ошибки

**Файл:** `data/network/YClientsRepository.kt:227–255`

401/403/сетевая ошибка → `null` без `handleUnauthorized`, без лога. Пользователь видит «Не удалось определить сотрудника» вместо «Сессия истекла», токены при 401 не чистятся.

**Фикс:** проверять `response.isSuccessful`, вызывать `handleUnauthorized`, пробрасывать `CancellationException`, логировать причину.

Коммит: `Добавил обработку ошибок в определение сотрудника`

### B7. Средне — нет retry/backoff на 408/429/5xx

**Файлы:** `data/network/YClientsClient.kt:64–81`, `YClientsRepository.kt`

Любой transient-сбой (429 rate limit, 5xx) — одна попытка и generic error; sync ломается до следующего poll.

**Фикс:** interceptor или retry-обёртка в репозитории: exponential backoff на 408/429/5xx и `IOException` (2–3 попытки).

Коммит: `Добавил retry с backoff для transient-ошибок API`

### B8. Средне — Gson: non-null поля + `setLenient()`

**Файлы:** `data/network/YClientsClient.kt:83–85`, `data/network/YClientsModels.kt:22–64`

Gson не проверяет Kotlin nullability: пропавшее поле → `null` в non-null `val` (`RecordData.id/date/attendance`, `AuthData.userToken`) → NPE в неожиданном месте. `setLenient()` дополнительно маскирует битые ответы.

**Фикс:** критичные поля сделать nullable с явной валидацией на границе (skip записи без id/date с логом); убрать `setLenient`.

Коммит: `Сделал модели YClients устойчивыми к неполному JSON`

### B9. Средне — `PushRegistrar.onLogout` fire-and-forget

**Файл:** `push/PushRegistrar.kt:46–52`, `sync/LogoutCoordinator.kt:34–37`

`LogoutCoordinator` не дожидается `unregister`; при быстром завершении процесса устройство остаётся на push-сервере.

**Фикс:** `suspend fun onLogout` и await из координатора.

Коммит: `Сделал unregister при logout ожидаемым`

---

## Волна C — производительность UI и память

### C1. Высоко — Gson-парс всего календаря + запись sync-кэша на Main

**Файлы:** `data/CalendarDataStore.kt:119–131` (`snapshotsFlow`), потребители `ui/calendar/CalendarViewModel.kt:44–57` (`stateIn(viewModelScope, Eagerly, ...)`)

`map` DataStore выполняется в контексте коллектора, а коллектор — `viewModelScope` (Main). При каждой эмиссии (каждый sync, каждое сохранение дня) на UI-потоке: `parseSnapshot` (Gson-парс всего календаря + архива) + `writeSyncCache` (SharedPreferences). На большой истории — jank вплоть до ANR.

**Фикс:** `.flowOn(Dispatchers.Default)` после `map` в `snapshotsFlow`; `writeSyncCache` — в `withContext(Dispatchers.IO)`. `cachedState.value = snapshot` потокобезопасен (StateFlow), но проверить отсутствие завязок на Main.

Коммит: `Перенёс парсинг снимков DataStore с main-потока`

### C2. Высоко — `ProfileContent` в закрытом drawer пересчитывает годовую статистику

**Файлы:** `ui/screens/CalendarScreen.kt:277–299`, `ui/profile/ProfileContent.kt:77–97`

`drawerContent` у `ModalNavigationDrawer` всегда в composition. `ProfileContent` подписан на `effectiveDayData` и на каждое изменение календаря пересчитывает `rememberProfileYearStats` (12 × `computeMonthStats` + парсинг всех сессий) — даже когда панель закрыта.

**Фикс:** композить содержимое drawer только при `drawerState.isOpen || drawerState.isAnimationRunning` (пустой `Box` иначе), либо перенести расчёт year stats в VM на `Dispatchers.Default` с debounce.

Коммит: `Убрал пересчёт статистики профиля в закрытом drawer`

### C3. Высоко — `CalendarScreen` подписан на полные карты

**Файл:** `ui/screens/CalendarScreen.kt:119–128, 126–128`

Корень экрана (~1000 строк) читает `dayData`, `savedDayData`, `effectiveDayData` целиком — любое изменение любой даты рекомпозит всё дерево, плюс `ArchiveSyncCompare.mismatchDates` пробегает все архивные ключи.

**Фикс:** в корне читать только `currentMonthDayData` + `selectedDate`; полные карты опускать в DayDetails/Profile через отдельные подписки; `mismatchDates` считать по текущему месяцу или в VM.

Коммит: `Сократил подписки CalendarScreen до текущего месяца`

### C4. Средне — состояние календаря не переживает process death

**Файлы:** `ui/calendar/CalendarViewModel.kt:34–41`, `ui/screens/CalendarScreen.kt:131`

Overlay восстанавливается (`OverlaySaver`), но месяц/выбранная дата/режим календаря/`highlightSlotKey` — нет. После смерти процесса восстановленный DayDetails откроется с «сегодня» вместо нужной даты.

**Фикс:** `SavedStateHandle` в `CalendarViewModel` для месяца/даты/режима; `highlightSlotKey` → `rememberSaveable`.

Коммит: `Сохранил состояние календаря при process death`

### C5. Средне — `collectAsState` без учёта lifecycle

**Файлы:** `CalendarScreen.kt`, `ProfileContent.kt`, `MainActivity.kt:157`, экраны настроек/auth

Подписки остаются активными в `STOPPED` — фоновый sync продолжает будить Compose-пайплайн свернутого приложения.

**Фикс:** зависимость `androidx.lifecycle:lifecycle-runtime-compose` + `collectAsStateWithLifecycle()` во всех экранах.

Коммит: `Перевёл подписки Compose на collectAsStateWithLifecycle`

### C6. Средне — `AppSettingsScreen`: нереактивное чтение prefs (остаток ETAP_4.9.2)

**Файл:** `ui/settings/AppSettingsScreen.kt:61–62`

`var autoSyncEnabled by remember { mutableStateOf(viewModel.isAutoSyncEnabled()) }` — одноразовое чтение; изменения извне экран не видит.

**Фикс:** StateFlow в `AppSettingsViewModel` по образцу theme/notification settings.

Коммит: `Сделал настройки AppSettings реактивными`

### C7. Средне — drag слота: чтение `Animatable` в composition

**Файл:** `ui/components/daydetails/ScheduleSlotItem.kt:322–326` (и ~463–465)

`expansion.value` читается в composition → рекомпозиция контейнера на каждый кадр drag/анимации.

**Фикс:** перенести чтение в layout/draw-фазу (`Modifier.graphicsLayer` / кастомный layout), не ветвить children по значению анимации.

Коммит: `Убрал покадровую рекомпозицию при drag слота`

---

## Волна D — хранилище, безопасность, конфигурация

### D1. Высоко — `EncryptedSharedPreferences`: fallback в plaintext + deprecated

**Файлы:** `data/network/TokenStorage.kt:113–134`, `gradle/libs.versions.toml` (`security-crypto:1.1.0-alpha06`)

При любой ошибке создания (corruption keyset, OEM-баги) токены молча пишутся в **обычные** SharedPreferences (`neiro_yclients_fallback`). Битый keyset не удаляется и не пересоздаётся. Сама библиотека Jetpack security-crypto — deprecated alpha.

**Фикс:** при ошибке — удалить `neiro_yclients_secure` + master key и повторить создание; при повторном провале — блокировать login с сообщением, а не писать токены plaintext. Долгосрочно — Tink или AES-GCM на Android Keystore.

Коммит: `Убрал plaintext-fallback хранилища токенов`

### D2. Средне — DataStore без `corruptionHandler`

**Файл:** `data/CalendarDataStore.kt:30`

Битый `calendar_data.preferences_pb` → `CorruptionException` → краш на первом же чтении (`warmUp`).

**Фикс:**

```kotlin
private val Context.dataStore by preferencesDataStore(
    name = "calendar_data",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)
```

(sync-кэш SharedPreferences смягчит потерю — снимок восстановится из него при следующем sync.)

Коммит: `Добавил corruptionHandler для DataStore`

### D3. Средне — `warmUp`/RMW из `cachedState` вне `dataStore.edit`

**Файл:** `data/CalendarDataStore.kt:108–117` (`warmUp` вне mutex), `:183–194` (`updateProfile` читает `cachedState`, пишет в edit)

Read-modify-write опирается на кэш в памяти, а не на актуальные prefs внутри `edit { }` — теоретическая потеря данных при записи до завершения `warmUp` с пустым sync-кэшем.

⚠️ Часть путей (архив: `saveDayToArchive`/`deleteDayFromArchive`) — **не трогать** по `OUT_OF_SCOPE.md`. Правка касается общего механизма (`updateProfile`, `warmUp` под mutex) и нового `updateDayData` из B1.

Коммит: `Сделал read-modify-write DataStore атомарным`

### D4. Средне — хардкод `companyId = 520135` и мёртвый `YClientsWebViewScreen`

**Файл:** `ui/yclients/YClientsWebView.kt:54` (+ неиспользуемый импорт `JavascriptInterface`, `:7`)

Экран нигде не вызывается (навигация ведёт на `AuthScreen`), но содержит захардкоженный ID филиала, который ETAP_5 убирал из `TokenStorage`.

**Фикс:** удалить экран целиком либо убрать дефолт и брать `TokenStorage.companyId`.

Коммит: `Удалил мёртвый YClientsWebViewScreen с хардкодом филиала`

### D5. Средне — лог сырого `errorBody`

**Файл:** `data/network/YClientsRepository.kt:335`

`Log.w(..., "Cannot parse error body: $errorBody", e)` — тело ответа API может содержать детали сессии; уходит в logcat на устройствах пользователей.

**Фикс:** логировать только HTTP-код и `e.message`.

Коммит: `Убрал сырое тело ошибки API из логов`

### D6. Высоко (архитектурное, частично OOS) — секреты в `BuildConfig` / APK

**Файл:** `app/build.gradle.kts:64–67` → `TokenStorage.kt:55–58`, `push/PushClient.kt:21`

`YCLIENTS_PARTNER_TOKEN` и `NEIRO_PUSH_API_KEY` лежат строками в release APK (извлекаются jadx/strings). Push-key — общий Bearer для register/unregister на своём сервере.

**Фикс (когда дойдут руки до backend):** proxy + short-lived токены; минимум — отдельный ограниченный device key вместо общего, ротация. В коде приложения сейчас можно только минимизировать поверхность.

### D7. Высоко (архитектурное, OOS) — YClients-токены уходят на push-backend

**Файл:** `push/PushRegistrar.kt:97–106`

`partner_token` + `user_token` передаются на свой сервер — компрометация сервера = полный доступ к YClients-аккаунтам. Зафиксировано как компромисс; правильное решение (opaque account_id + JWT) требует переделки backend.

### D8. Низко — прочее по хранилищу/сборке

- **JSON-блобы:** весь календарь одной строкой в Preferences DataStore + нешифрованное зеркало в `neiro_sync_cache` — долгосрочно Proto DataStore/Room или помесячные ключи (`CalendarDataStore.kt:32–34, 328–344`).
- **ProGuard:** слишком широкие `-keep` для `gson.**`, `retrofit2.**`, `okhttp3.**` (`proguard-rules.pro:11, 31–42`) — сузить до моделей.
- **Версии:** Compose BOM 2024.10.00 устарел (bump — осознанно OOS), core-ktx 1.13.0 отстаёт от compileSdk 35.
- **`UserProfile` дефолты:** персональные суммы владельца в коде (`UserProfile.kt:30–32`) — заменить на нули при желании.
- **D2D-перенос:** `data_extraction_rules.xml` включает `datastore/` (профиль с PII) при device-to-device — by design, cloud исключён.

---

## Волна E — низкий приоритет (по остаточному принципу)

| # | Проблема | Файл | Фикс |
|---|----------|------|------|
| E1 | Коллизии notification id: `dedupeKey.hashCode()` может пересечься с фиксированными id 10001–10005; отрицательный hash у archive-reminder выходит из диапазона | `SessionNotificationDisplay.kt:166, 192, 218, 247` | namespace: `stableHash(...) and 0x7fffffff` + смещение в выделенный диапазон |
| E2 | Digest как `setGroupSummary(true)` в общей группе с events — странная группировка в шторке | `SessionNotificationDisplay.kt:20, 75–78, 96–99` | отдельные group key или убрать summary |
| E3 | Claim digest записывается до гарантированного показа; `notify()` глотает `SecurityException` | `SessionNotificationCoordinator.kt:520–523`, `SessionNotificationDisplay.kt:285–290` | `show*` возвращает Boolean, при false — откат claim |
| E4 | `isLoggedIn.value` вместо `first()` — скип уведомлений при неготовом init | `SessionNotificationCoordinator.kt:96` | `isLoggedIn.first()` |
| E5 | Archive-LRU через `HashSet.first()` — удаляется случайный ключ | `SessionNotificationPreferences.kt:179–208` | ordered-list LRU как у `notified_event_keys_v2` |
| E6 | Повторный FCM во время running sync отбрасывается (`KEEP`), изменения ждут keepalive 30–60 мин | `PushSyncCoordinator.kt:20–24` | follow-up enqueue после успеха при «pending FCM» |
| E7 | Пароль остаётся в `AuthViewModel.StateFlow` после ухода с экрана | `AuthViewModel.kt:50–68` | чистить в `onCleared`/при back |
| E8 | Нет `callTimeout` у OkHttp-клиентов | `YClientsClient.kt:64–68`, `PushClient.kt:25–29` | `callTimeout(60s)` |
| E9 | `currentNames.toList()` в composition — аллокации на каждый рекомпоз | `DayDetailsDialog.kt:223, 348` | `derivedStateOf`/версия-счётчик |

---

## Не трогать (подтверждено аудитом, см. OUT_OF_SCOPE.md)

- Сброс локальных правок в `DayDetailsDialog` при фоновом sync (`LaunchedEffect(initialNames)`).
- Authoritative wipe текущего месяца в `YClientsCalendarSync`.
- Архив: `saveDayToArchive`/`deleteDayFromArchive`/`writeSyncCache` без `savedDayJson`.
- God-composables (CalendarScreen/DayDetails/Profile) — не дробить ради дробления (C2/C3 — точечные правки подписок, не декомпозиция).
- `formatCalendarCounts` без кэша в сетке, `entries.map` без remember, полный rewrite JSON in-app уведомлений.
- Неполная M3-палитра / dynamic color.
- Bump Compose BOM и прочих версий — отдельным решением пользователя.

---

## Рекомендуемый порядок выполнения

```
Волна A (A1 → A2 → A3 → A4 → A5 → A6 → A7 → A8)
  → Волна B (B2 → B1 → B3 → B7 → B8 → B4 → B5 → B6 → B9)
  → Волна C (C1 → C2 → C3 → C5 → C4 → C6 → C7)
  → Волна D (D1 → D2 → D3 → D4 → D5)
  → Волна E (по желанию)
D6/D7 — отдельный backend-проект.
```

Логика: сначала возвращаем надёжность фона (A) — это молчаливые отказы, которые пользователь не видит; затем целостность данных и сети (B); затем плавность UI (C); затем защита хранилища (D).

## Проверки после волн

- **A:** уйти в фон без FCM → убедиться через WorkManager Inspector, что `yclients_live_api_refresh` перепланируется после каждого запуска; включить сводки → выполнить sync за минуту до времени сводки → сводка всё равно приходит.
- **B:** отредактировать день во время активного sync → правка не теряется; включить авиарежим во время sync → нет `ApiResult.Error` от отмены; протухший токен → полный logout с unregister.
- **C:** Layout Inspector (recomposition counts) при фоновом sync: закрытый drawer и ячейки чужих месяцев не рекомпозятся; профилировщик — нет Gson-парса на main.
- **D:** испортить `calendar_data.preferences_pb` → приложение стартует с пустым календарём, не крашится; посмотреть strings в release APK до/после.
