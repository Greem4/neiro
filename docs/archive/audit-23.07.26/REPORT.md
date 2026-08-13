# Отчёт по аудиту 23.07.26 — что сделано

Выполнены волны 1–7 из [ROADMAP.md](ROADMAP.md) — все пункты из [FINDINGS.md](FINDINGS.md) серьёзностью «Критично», «Высоко» и «Средне», плюс часть «Низко», вошедшая в волны. Ограничения из [`docs/audit-17.07.26/OUT_OF_SCOPE.md`](../audit-17.07.26/OUT_OF_SCOPE.md) соблюдены. Не сделано и почему — в конце документа.

Пути сокращены от `app/src/main/java/ru/greemlab/neiro/`.

## Волна 1 — безопасность и критичные фоны

| # | Что сделано и почему | Код |
|---|---|---|
| B1 | Из git убран собранный `app/release/app-release.aab` с запечёнными `NEIRO_PUSH_API_KEY`/`YCLIENTS_PARTNER_TOKEN` — бинарь в истории репозитория означал, что ключи извлекаются `strings`-ом без реверса; в `.gitignore` добавлены `*.aab`, `*.apk`, `app/release/`. **Ротацию ключей на push-сервере агент не выполняет — это ручное действие пользователя.** | `.gitignore` |
| S1 | `finally` в keepalive/live-воркерах планировал следующее звено цепочки даже после отмены при logout — воркеры «воскресали» и жили вечно; `runCatching` глотал `CancellationException`, из-за чего отменённая корутина отчитывалась как успешная | [`push/PushKeepAliveWorker.kt`](../../../app/src/main/java/ru/greemlab/neiro/push/PushKeepAliveWorker.kt), [`sync/LiveApiRefreshWorker.kt`](../../app/src/main/java/ru/greemlab/neiro/sync/LiveApiRefreshWorker.kt) |
| S2 | `LiveApiRefreshWorker` при ошибке одновременно возвращал `Result.retry()` и сам ставил следующее звено — очередь монотонно раздувалась при нестабильной сети | [`sync/LiveApiRefreshWorker.kt`](../../app/src/main/java/ru/greemlab/neiro/sync/LiveApiRefreshWorker.kt) |
| N1 | Напоминания терялись навсегда: fallback-окно ±7 мин было уже периода тика (15 мин), и просроченный `delayMs` вообще не ставил one-time воркер. Расширил окно, добавил catch-up для уже наступившего, но ещё не показанного напоминания, и enqueue с нулевой задержкой для просроченного расчёта | [`notifications/UpcomingSession.kt`](../../../app/src/main/java/ru/greemlab/neiro/notifications/UpcomingSession.kt), [`notifications/SessionNotificationCoordinator.kt`](../../../app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationCoordinator.kt) |

## Волна 2 — синхронизация и push

| # | Что сделано и почему | Код |
|---|---|---|
| S4 | Перенос записи YClients на другой день оставлял «призрака» в старом дне: `changed_after` отдаёт запись уже с новой датой, а refetch шёл только по окну изменившихся дат. Инкрементальный sync текущего месяца теперь перечитывает весь месяц авторитативно | [`sync/YClientsCalendarSync.kt`](../../../app/src/main/java/ru/greemlab/neiro/sync/YClientsCalendarSync.kt) |
| S3 | При активном push отключался и foreground-поллинг — при задержке FCM открытый календарь устаревал сильнее, чем без push вовсе. Вернул поллинг раз в 5 мин, пока экран открыт | [`sync/LiveApiCoordinator.kt`](../../../app/src/main/java/ru/greemlab/neiro/sync/LiveApiCoordinator.kt) |
| S5 | Logout не отменял воркер `push_fcm_sync` — он мог уйти в retry и работать поверх уже очищенного состояния аккаунта | [`auth/LogoutCoordinator.kt`](../../../app/src/main/java/ru/greemlab/neiro/auth/LogoutCoordinator.kt) |
| S6 | Keepalive глотал ошибки синка без retry — следующий шанс был только через 30–60 мин. Добавил `Result.retry()` при неудаче | [`push/PushKeepAliveWorker.kt`](../../../app/src/main/java/ru/greemlab/neiro/push/PushKeepAliveWorker.kt) |
| S7 | Неудачный unregister устройства терялся молча — устройство оставалось зарегистрированным и получало push для чужого аккаунта | [`push/PushRegistrar.kt`](../../../app/src/main/java/ru/greemlab/neiro/push/PushRegistrar.kt) |
| S8 | Проверка конфигурации push была рассинхронизирована между координаторами (`isServerConfigured` vs `isActive`) | [`push/PushSyncCoordinator.kt`](../../app/src/main/java/ru/greemlab/neiro/push/PushSyncCoordinator.kt) |
| S9 | Дублирующееся имя live-воркера в двух файлах вынесено в общую константу; поправлен устаревший комментарий про ночное окно | [`auth/LogoutCoordinator.kt`](../../../app/src/main/java/ru/greemlab/neiro/auth/LogoutCoordinator.kt), [`sync/LiveApiCoordinator.kt`](../../../app/src/main/java/ru/greemlab/neiro/sync/LiveApiCoordinator.kt) |

## Волна 3 — уведомления

| # | Что сделано и почему | Код |
|---|---|---|
| N3 | Смена времени утренней сводки в UI шла через cancel + `KEEP` — асинхронный cancel мог не успеть, и новый enqueue отбрасывался; отменённый digest-воркер к тому же перепланировал себя заново в `finally`. UI-путь теперь один `enqueue(REPLACE)`, воркер не перепланирует себя при `isStopped` | [`notifications/SessionNotificationCoordinator.kt`](../../../app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationCoordinator.kt), [`notifications/SessionScheduledDigestWorker.kt`](../../../app/src/main/java/ru/greemlab/neiro/notifications/SessionScheduledDigestWorker.kt) |
| N2 | Без разрешения POST_NOTIFICATIONS не велась даже внутриприложенческая лента событий — diff одноразовый, следующий sync те же изменения не покажет. In-app запись и `mark*` теперь выполняются всегда, системный push — отдельно, по разрешению | [`notifications/SessionNotificationCoordinator.kt`](../../../app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationCoordinator.kt) |
| N4 | Периодический воркер сбрасывался (`UPDATE`) на каждый холодный старт, сбивая фазу тика и усиливая N1. Теперь `KEEP` при инициализации, `UPDATE` — только при смене настроек | [`notifications/SessionNotificationCoordinator.kt`](../../../app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationCoordinator.kt) |
| N9 | Каждый изменивший календарь sync пересоздавал все reminder-воркеры разом — на живом поллинге (до раза в 5 мин) это был главный усилитель потери напоминаний. Заменил на diff-перепланирование только изменившихся ключей | [`notifications/SessionNotificationCoordinator.kt`](../../../app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationCoordinator.kt) |
| N5 | `slotKey` не включал тип сессии — урок и диагностика одного клиента в одно время схлопывались в один ключ и путали детектор изменений | [`notifications/SessionSlotKey.kt`](../../../app/src/main/java/ru/greemlab/neiro/notifications/SessionSlotKey.kt) и зависимые файлы |
| N6 | Утренняя сводка «сегодня» отбрасывала уже начавшиеся/прошедшие слоты — при доставке с опозданием (Doze) занятия дня пропадали из сводки | [`notifications/UpcomingSession.kt`](../../../app/src/main/java/ru/greemlab/neiro/notifications/UpcomingSession.kt) |
| N7 | Logout не чистил архивные claim-ключи (LRU напоминаний архива) вместе с остальным состоянием аккаунта | [`notifications/SessionNotificationCoordinator.kt`](../../../app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationCoordinator.kt) |
| N8 | `mark*Notified` для событий/напоминаний выполнялся даже если показ упал с `SecurityException` — уведомление считалось показанным, хотя пользователь его не увидел. По образцу дайджестов добавил откат dedupe при неудаче | [`notifications/SessionNotificationDisplay.kt`](../../../app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationDisplay.kt) |
| N10 | Удалён мёртвый `calendar_snapshot` — писался, но нигде не читался (diff считается по in-memory before/after) | [`notifications/SessionNotificationPreferences.kt`](../../../app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationPreferences.kt) |

## Волна 4 — данные и сеть

| # | Что сделано и почему | Код |
|---|---|---|
| D1 | Гонка 401 и повторного входа: хвост асинхронного logout мог затереть уже поднятую пользователем новую сессию. Зафиксировал поколение сессии при входе — хвост чистит состояние, только если сессия не сменилась | [`data/network/YClientsRepository.kt`](../../../app/src/main/java/ru/greemlab/neiro/data/network/YClientsRepository.kt), [`auth/LogoutCoordinator.kt`](../../../app/src/main/java/ru/greemlab/neiro/auth/LogoutCoordinator.kt) |
| D2 | Смена цены занятия читала устаревший `cachedState`, а не DataStore, и перезаписывала им весь профиль — параллельный sync мог откатить остальные поля. Теперь read-modify-write внутри `dataStore.edit` | [`data/CalendarDataStore.kt`](../../../app/src/main/java/ru/greemlab/neiro/data/CalendarDataStore.kt) |
| D3 | `snapshotsFlow` был холодным с побочными эффектами (парсинг + запись sync-кэша) на каждую независимую подписку — N коллекторов давали N парсов и гонку записи. Обернул в `shareIn(Eagerly, replay=1)` | [`data/CalendarDataStore.kt`](../../../app/src/main/java/ru/greemlab/neiro/data/CalendarDataStore.kt) |
| D5 | Повторный вход другим сотрудником без полного logout оставлял старый `staffId` — показывались чужие/пустые записи. Сбрасываю `staffId` и делаю повторный detect при login | [`data/network/YClientsRepository.kt`](../../../app/src/main/java/ru/greemlab/neiro/data/network/YClientsRepository.kt) |
| D4 | После `clearAllData` в sync-кэше оставался старый архив (`saved_day_data_json`) — на следующем холодном старте кратко показывался устаревший архив | [`data/CalendarDataStore.kt`](../../../app/src/main/java/ru/greemlab/neiro/data/CalendarDataStore.kt) |
| D6 | Импорт бэкапа писал уведомления раньше DataStore — сбой записи календаря оставлял уведомления импортированными наполовину. Переставил порядок: сначала DataStore, потом уведомления | [`data/CalendarDataStore.kt`](../../../app/src/main/java/ru/greemlab/neiro/data/CalendarDataStore.kt) |
| D7 | Инициализация `TokenStorage` (MasterKey + EncryptedSharedPreferences, диск + KeyStore I/O) шла на первом обращении, часто на main-потоке. Прогреваю на `Dispatchers.IO` при старте | [`data/network/TokenStorage.kt`](../../../app/src/main/java/ru/greemlab/neiro/data/network/TokenStorage.kt) |
| D8 | `RetryInterceptor` не учитывал `Retry-After` и ретраил неидемпотентные POST-запросы | [`data/network/YClientsClient.kt`](../../../app/src/main/java/ru/greemlab/neiro/data/network/YClientsClient.kt) |
| D9 | Убран неиспользуемый `YClientsApi.getRecord`, лишний импорт, `Gson()` в `parseErrorMessage` больше не создаётся на каждый вызов | [`data/network/YClientsApi.kt`](../../../app/src/main/java/ru/greemlab/neiro/data/network/YClientsApi.kt), [`YClientsRepository.kt`](../../../app/src/main/java/ru/greemlab/neiro/data/network/YClientsRepository.kt) |
| D10 | `exportAllData` мог читать данные во время параллельной записи (torn read) — обернул в тот же `writeMutex` | [`data/CalendarDataStore.kt`](../../../app/src/main/java/ru/greemlab/neiro/data/CalendarDataStore.kt) |

## Волна 5 — UI календаря и парсер

| # | Что сделано и почему | Код |
|---|---|---|
| U1 | Кнопка «перезаписать архив из синка» была недостижима — ветка `allowStatusEdit` перехватывала все архивные дни раньше проверки mismatch. Переставил порядок веток | [`ui/components/DayDetailsDialog.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/components/DayDetailsDialog.kt) |
| U2 | Интенсив без времени с непустыми детьми ломал позиционный парсинг: пустое поле времени не писалось, и первый ребёнок читался как время, остальные калечились. Слот времени теперь сериализуется всегда, если есть дети; добавлен round-trip тест | [`ui/calendar/SessionParser.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/calendar/SessionParser.kt) |
| U3 | После смерти процесса открытый день мгновенно закрывался — `selectedDayContext` стартовал с `null`, и первый кадр сбрасывал восстановленный overlay | [`ui/screens/CalendarScreen.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/screens/CalendarScreen.kt), [`ui/calendar/CalendarViewModel.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/calendar/CalendarViewModel.kt) |
| U4 | Жест сворачивания слота замен/интенсивов использовал устаревшее (stale closure) значение `expanded`/`onToggle`, из-за чего слот «отпрыгивал» обратно после раскрытия | [`ui/components/daydetails/ScheduleSlotItem.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/components/daydetails/ScheduleSlotItem.kt) |
| U5 | «Итог» дня считал интенсив со всеми статусами, а не только пришедшими — расходился с месячной статистикой (`onlyArrived=true`) | [`ui/components/DayDetailsDialog.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/components/DayDetailsDialog.kt) |
| U7 | Комментарий ученика с `|` внутри обрезался при парсинге расширенного формата (`split` без `limit`) | [`ui/calendar/SessionParser.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/calendar/SessionParser.kt) |
| U6 | Баннер расхождения архива не показывал детали при разнице только в `amountFixed` — диффалась только числовая сумма | [`ui/calendar/ArchiveSyncCompare.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/calendar/ArchiveSyncCompare.kt) |
| U8 | После восстановления после process death на миг показывался неверный месяц/режим — initial-значения игнорировали `SavedStateHandle` | [`ui/calendar/CalendarViewModel.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/calendar/CalendarViewModel.kt) |
| U9 | `computeDayStats` парсил строки сессий дважды за один расчёт | [`ui/components/DaySummaryStats.kt`](../../app/src/main/java/ru/greemlab/neiro/ui/components/DaySummaryStats.kt) |

## Волна 6 — профиль, настройки, auth

| # | Что сделано и почему | Код |
|---|---|---|
| P2 | `login()` не защищался от повторного сабмита — двойной тап/IME Done запускал два параллельных запроса входа | [`ui/auth/AuthViewModel.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/auth/AuthViewModel.kt) |
| P1 | «Сменить аккаунт» сразу открывал форму входа, пока асинхронный logout ещё не завершился — показывался старый залогиненный экран | [`ui/profile/SettingsScreen.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/profile/SettingsScreen.kt), [`ui/sync/SyncViewModel.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/sync/SyncViewModel.kt) |
| P3 | Master-тумблер уведомлений хранился в двух независимых ViewModel — смена на детальном экране не долетала до Activity-scoped VM | [`ui/settings/AppSettingsViewModel.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/settings/AppSettingsViewModel.kt), [`ui/settings/SessionNotificationSettingsViewModel.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/settings/SessionNotificationSettingsViewModel.kt) |
| P4 | Годовая сводка включала интенсивы в сумму денег, но не в счётчик занятий — месяц с одним интенсивом выглядел как «0 занятий, но деньги есть» | [`ui/calendar/CalendarStatsCalculator.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/calendar/CalendarStatsCalculator.kt), [`ui/profile/ProfileYearStatsSection.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/profile/ProfileYearStatsSection.kt) |
| P5 | Налог в «Финансах» отображался полной суммой даже когда превышал заработок месяца | [`ui/calendar/CalendarStatsCalculator.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/calendar/CalendarStatsCalculator.kt), [`ui/components/CalendarDialogs.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/components/CalendarDialogs.kt) |
| P6 | Два разных условия завершения регистрации (кнопка требовала вид деятельности, «назад» — только имя) | [`ui/profile/SettingsScreen.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/profile/SettingsScreen.kt) |
| P7 | Смена цены занятия переведена на общую очередь обновлений профиля (после D2) | [`ui/profile/ProfileViewModel.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/profile/ProfileViewModel.kt) |
| P8 | Убран мёртвый API автосинка в `SyncViewModel`, не использовавшийся UI | [`ui/sync/SyncViewModel.kt`](../../../app/src/main/java/ru/greemlab/neiro/ui/sync/SyncViewModel.kt) |

## Волна 7 — сборка, тесты, гигиена

| # | Что сделано и почему | Код |
|---|---|---|
| B3 | Единственный instrumented-тест всегда падал на debug-сборке (`.debug` suffix против захардкоженного `ru.greemlab.neiro`) — сравнение перевёл на `BuildConfig.APPLICATION_ID` | `app/src/androidTest/.../ExampleInstrumentedTest.kt` |
| B2 | Baseline profile ссылался на устаревшую сигнатуру `CalendarViewModel(Application)` — правило не матчилось, AOT для этого пути не работал. Поправил под текущий конструктор `(Application, SavedStateHandle)` | `app/src/main/baseline-prof.txt` |
| B5 | Из git убраны 140 файлов `scripts/icon/node_modules` | `.gitignore` |
| B4 | Добавлен минимальный CI (`testDebugUnitTest` + запрет коммита aab/apk/google-services.json в диффе) — юнит-тесты (~130) раньше никто не гонял автоматически | `.github/workflows/ci.yml` |
| B7 | Убран `en` из `localeFilters` — переводов для него нет, ресурсы только `values/` (русский) | `app/build.gradle.kts` |
| B6 | Обновил core-ktx, lifecycle, activity-compose, WorkManager до последних версий, ещё совместимых с `compileSdk 35`/AGP 9.2.1 (более новые требуют compileSdk 36–37 — отдельный заход, как и Firebase BOM 33→34) | `gradle/libs.versions.toml` |
| B8 | `enableBaselineProfile = false` противоречил наличию `profileinstaller` — включил, чтобы `installRelease` тоже ставил профиль | `app/build.gradle.kts` |

## Что не сделано и почему

- **B9** (`FOREGROUND_SERVICE` без type) — риска нет, пока WorkManager не начинает `setForegroundAsync`; отслеживать при переходе на targetSdk 34+. Роадмап явно вынес это «вне дорожной карты».
- **B10** (тесты) — округление round-trip интенсива без времени (U2) и окна reminder (N1) уже покрыты юнит-тестами, добавленными вместе с самими фиксами. Тест на перенос записи (S4) и smoke-тест `TokenStorage` не написаны: `YClientsRepository`/`TokenStorage` берут `Context` напрямую в конструкторе без интерфейса, и мокать сеть/KeyStore без такой абстракции нельзя — а вводить DI-прослойку ради теста явно вынесено в out-of-scope обоих аудитов («DI/Room/разбиение god-composables — не трогаем»).
- **N11, N12, U10–U12, P9** — низкая серьёзность, в ROADMAP.md помечены «по остаточному принципу» (необязательные, за пределами волн).
- **Ротация `NEIRO_PUSH_API_KEY`/partner token** (часть B1) и переписывание git-истории — не код-правки, требуют действий пользователя на push-сервере и решения о переписывании хэшей всех веток.
- **Firebase BOM 33→34** — сознательно отдельным заходом: релиз убирает KTX-артефакты, ломающее изменение.

## Проверки — см. ROADMAP.md

Чек-лист «Проверки после волн» в [ROADMAP.md](ROADMAP.md#проверки-после-волн) не выполнялся агентом (сборку и тесты гоняет пользователь) — рекомендуется пройти его перед релизом ветки `аудит-4`.
