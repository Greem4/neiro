# Отчёт по аудиту 17.07.26 — что сделано

Выполнены все волны A–E из [ROADMAP.md](ROADMAP.md). Ограничения из [OUT_OF_SCOPE.md](OUT_OF_SCOPE.md) соблюдены: архив, `LaunchedEffect(initialNames)`, authoritative wipe текущего месяца не тронуты. D6/D7 (секреты в APK, YClients-токены на push-backend) — архитектурные, требуют backend-работ, в этот заход не входили.

Учтено требование: полная история хранится на телефоне и не перезапрашивается; активно синхронизируется только «живой» диапазон, а при точечных изменениях — только затронутые дни (B4).

Все пути ниже — от `app/src/main/java/ru/greemlab/neiro/`.

## Волна A — фоновые задачи

| # | Что сделано | Код |
|---|-------------|-----|
| A1 | Self-reschedule фонового опроса теперь `APPEND_OR_REPLACE`: `KEEP` молча отбрасывал новый запрос, пока worker в RUNNING, и цепочка опросов обрывалась | `sync/LiveApiCoordinator.kt` → `scheduleNextBackgroundRefresh` |
| A2 | Keepalive-worker: работа обёрнута в `runCatching`, перепланирование — в `finally`; цепочка не рвётся при ошибке сети | `push/PushKeepAliveWorker.kt`, `push/PushKeepAliveCoordinator.kt` → `scheduleNext` |
| A3, A6 | Digest-worker перепланирует себя в `finally` через `rescheduleDigestFromWorker` (без cancel, `APPEND_OR_REPLACE`); из sync-пути — только `ensureAllDailyDigestsScheduled` (KEEP, без cancel запущенного worker'а) | `notifications/SessionNotificationCoordinator.kt`, `notifications/SessionScheduledDigestWorker.kt` |
| A4 | `PushSyncWorker` возвращает `Result.retry()` при `SyncOutcome.Failure`, а не только при исключении | `push/PushSyncWorker.kt` |
| A5 | Constraint `NetworkType.CONNECTED` для FCM-sync | `push/PushSyncCoordinator.kt` |
| A7 | Два периодических worker'а слиты в один суточный; напоминания проверяются и из него (`runReminderCheck`) | `notifications/SessionNotificationCoordinator.kt` → `scheduleDailyNotifications`, `runReminderCheck`; `SessionReminderWorker.kt`, `SessionDailyNotificationWorker.kt` |
| A8 | BootReceiver: `goAsync()` + корутина, prefs-чтение и enqueue не на main | `notifications/SessionNotificationBootReceiver.kt` |

## Волна B — целостность данных и сеть

| # | Что сделано | Код |
|---|-------------|-----|
| B1 | Атомарный read-modify-write календаря: `updateDayData(transform)` под `Mutex` вместо `saveDayData(map)`; параллельные sync и ручные правки не затирают друг друга | `data/CalendarRepository.kt`, `data/CalendarDataStore.kt` → `updateDayData`; вызовы в `ui/calendar/CalendarViewModel.kt` и `sync/YClientsCalendarSync.kt` |
| B2 | `CancellationException` пробрасывается из всех catch-блоков репозитория — отмена корутин не превращается в `ApiResult.Error` | `data/network/YClientsRepository.kt` |
| B3 | 401 → полный logout через `LogoutCoordinator` (снятие push-регистрации, чистка состояния), c `AtomicBoolean`-защитой от повторных вызовов | `data/network/YClientsRepository.kt` → `handleUnauthorized` |
| B4 | Инкрементальный sync перезапрашивает не весь текущий месяц, а только диапазон затронутых дат | `sync/YClientsCalendarSync.kt` → `mergeIncrementalRecords` |
| B5 | Пагинация завершается по `meta.totalCount`, а не по эвристике размера страницы | `data/network/YClientsRepository.kt` → `fetchRecords`, `getClients` |
| B6 | `detectAndSaveStaffId`: проверка кода ответа, обработка 401/403, логирование причин | `data/network/YClientsRepository.kt` |
| B7 | `RetryInterceptor`: авто-повтор 408/429/5xx и `IOException` с экспоненциальной задержкой | `data/network/YClientsClient.kt` |
| B8 | Убран `Gson.setLenient()`; `AuthData.userToken` и `RecordData.date` — nullable, битые записи отфильтровываются (`isValidRecord`) | `data/network/YClientsModels.kt`, `YClientsRepository.kt` |
| B9 | `PushRegistrar.onLogout` — suspend; logout дожидается снятия регистрации устройства | `push/PushRegistrar.kt`, `auth/LogoutCoordinator.kt` |

## Волна C — производительность UI

| # | Что сделано | Код |
|---|-------------|-----|
| C1 | Парсинг снапшота DataStore ушёл с main: `flowOn(Dispatchers.Default)` | `data/CalendarDataStore.kt` → `snapshotsFlow` |
| C2 | Контент drawer'а (годовая статистика) компонуется только когда drawer открыт или анимируется | `ui/screens/CalendarScreen.kt` |
| C3 | Корень экрана не подписан на полные словари `dayData`/`syncedDayData`/`savedDayData`; вместо них — `currentMonthDayData` и `selectedDayContext` (данные только выбранного дня) | `ui/calendar/CalendarViewModel.kt`, `ui/screens/CalendarScreen.kt` |
| C4 | Восстановление после process death: месяц, выбранный день и режим календаря — в `SavedStateHandle`; `highlightSlotKey` — `rememberSaveable` | `ui/calendar/CalendarViewModel.kt`, `ui/screens/CalendarScreen.kt` |
| C5 | Все `collectAsState()` заменены на `collectAsStateWithLifecycle()` (новая зависимость `lifecycle-runtime-compose`) | все Compose-экраны; `gradle/libs.versions.toml`, `app/build.gradle.kts` |
| C6 | Настройки автосинка и уведомлений — реактивные `StateFlow` во ViewModel вместо разовых чтений | `ui/settings/AppSettingsViewModel.kt`, `AppSettingsScreen.kt` |
| C7 | Раскрытие слотов расписания без рекомпозиции на каждый кадр: значение анимации читается в layout-фазе (`ExpansionSplitLayout`), пороги видимости — через `derivedStateOf` | `ui/components/daydetails/ScheduleSlotItem.kt` |

## Волна D — хранилище и безопасность

| # | Что сделано | Код |
|---|-------------|-----|
| D1 | Убран plaintext-fallback токенов. При ошибке шифрованного хранилища: удаление битого keyset + master key и повтор; при повторном провале — только in-memory (на диск не пишется). Легаси plaintext-файл удаляется, когда шифрование работает | `data/network/TokenStorage.kt` → `createSecurePrefs`, `InMemoryPrefs` |
| D2 | `corruptionHandler` у DataStore: битый файл не роняет приложение, снапшот восстановится из sync-кэша | `data/CalendarDataStore.kt` |
| D3 | `warmUp` под writer-mutex; `updateProfile` читает актуальные prefs внутри `edit`, а не кэш | `data/CalendarDataStore.kt` |
| D4 | Удалён мёртвый `YClientsWebViewScreen` с захардкоженным `companyId` | удалён `ui/yclients/YClientsWebView.kt` |
| D5 | Из логов убрано сырое тело ошибки API (только HTTP-код и сообщение) | `data/network/YClientsRepository.kt` → `parseErrorMessage` |
| D8 | ProGuard: убраны широкие `-keep` для `gson.**`/`retrofit2.**`/`okhttp3.**` (библиотеки несут consumer-rules); добавлен keep конструктора `(Application, SavedStateHandle)` для ViewModel | `app/proguard-rules.pro` |

## Волна E — мелочи

| # | Что сделано | Код |
|---|-------------|-----|
| E1 | Динамические notification id — в выделенном диапазоне от 20 000, hash всегда неотрицательный; коллизии с фиксированными id 10 001–10 005 исключены | `notifications/SessionNotificationDisplay.kt` → `dynamicNotificationId`, `stableHash` |
| E2 | Раздельные group key для events и reminders; сводки больше не group-summary в чужой группе | `notifications/SessionNotificationDisplay.kt` |
| E3 | `show*Digest`/`showArchiveReminder` возвращают `Boolean`; при неудачном показе claim откатывается — сводка не теряется на весь день | `SessionNotificationDisplay.kt`, `SessionNotificationCoordinator.kt` → `maybeShow*` |
| E4 | `isLoggedIn.first()` вместо `.value` в sync-пути уведомлений | `notifications/SessionNotificationCoordinator.kt` |
| E5 | LRU архивных напоминаний — упорядоченный список (`archive_reminder_epoch_days_v2`) вместо `HashSet.first()`, удалявшего случайный ключ | `notifications/SessionNotificationPreferences.kt` |
| E6 | Повторный FCM во время идущего sync встаёт в очередь (`APPEND_OR_REPLACE`), а не отбрасывается до keepalive | `push/PushSyncCoordinator.kt` |
| E7 | Пароль очищается при уходе с экрана авторизации и в `onCleared` | `ui/auth/AuthViewModel.kt`, `AuthScreen.kt` |
| E8 | `callTimeout(60s)` у обоих OkHttp-клиентов | `data/network/YClientsClient.kt`, `push/PushClient.kt` |
| E9 | `derivedStateOf` вместо `remember(currentNames.toList())` — без аллокаций списка на каждый рекомпоз диалога дня | `ui/components/DayDetailsDialog.kt` |

## Структура документации

- `docs/audit-17.07.26/` — этот аудит: `ROADMAP.md` (план), `OUT_OF_SCOPE.md` (что не трогаем), `REPORT.md` (этот отчёт).
- `docs/refactoring-old/` — старый пакет рефакторинга (этапы 1–6, выполнены ранее).

## Как проверить (из ROADMAP)

- **A:** WorkManager Inspector — `yclients_live_api_refresh` перепланируется после каждого запуска; sync за минуту до времени сводки не отменяет её показ.
- **B:** правка дня во время активного sync не теряется; авиарежим во время sync не даёт ложных ошибок; протухший токен → полный logout.
- **C:** Layout Inspector — закрытый drawer и чужие месяцы не рекомпозятся при фоновом sync.
- **D:** испорченный `calendar_data.preferences_pb` → старт с пустым календарём без краша; в release APK нет plaintext-fallback токенов.
