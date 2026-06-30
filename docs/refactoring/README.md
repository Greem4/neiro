# Рефакторинг Neiro: план по этапам

Этот пакет — пошаговая инструкция для исправления проблем, найденных в аудите кода `app/`. Документация рассчитана на агента/разработчика, который будет выполнять правки **без устных уточнений**: каждый этап содержит конкретные файлы, строки, диффы и acceptance criteria.

## Принципы

1. **Не запускать Gradle.** Сборку и проверку выполняет пользователь. Агент пишет код, фиксирует логические правки, проверяет линты внутри IDE (`ReadLints`).
2. **Один этап = одна сессия = серия маленьких коммитов.** Коммиты — по правилам репо: одна короткая строка на русском, прошедшее время, без префиксов вроде `feat:`/`fix:` (см. `.cursorrules`).
3. **Этапы независимы друг от друга**, но порядок выполнения важен (есть зависимости — указаны в шапке этапа).
4. **Поведение приложения не меняем там, где это критично:**
    - **Архив (личный календарь, `CalendarMode.PERSONAL`)** — поведение «сохраняется и не затирается при sync» сохраняется как есть.
    - **Основной (синхронизированный) календарь** — авторитативный источник YClients; локальные правки в нём могут перезаписываться при sync (это `by design`, не баг).
    - **`DayDetailsDialog` — `LaunchedEffect(initialNames)` не трогаем**: затерание локальных правок основного календаря при фоновом sync — by design.

## Этапы

| # | Файл | Что делаем | Уровень риска | Зависимости |
|---|------|-----------|---------------|-------------|
| 1 | [ETAP_1_security_configs.md](ETAP_1_security_configs.md) ✅ | Логи/ProGuard/Manifest/backup/конфиги | Низкий | — |
| 2 | [ETAP_2_sync_push.md](ETAP_2_sync_push.md) | Watermark, REPLACE→KEEP, retry, polling, LogoutCoordinator | **Высокий** | 1 (для ProGuard keep + logging) |
| 3 | [ETAP_3_notifications.md](ETAP_3_notifications.md) ✅ | Reminder window, claim, permission, stable hash | Средний | 2 (LogoutCoordinator) |
| 4 | [ETAP_4_ui.md](ETAP_4_ui.md) | WebView destroy, SyncVM mutex, LazyColumn keys, a11y | Низкий | — |
| 5 | [ETAP_5_data.md](ETAP_5_data.md) ✅ | 401, pagination, MIN_NAME_MATCH=2, nullable JSON, логи | Средний | — |
| 6 | [ETAP_6_core.md](ETAP_6_core.md) | Async init, edge-to-edge dedup, deep-link state | Низкий | 1 (backup rules) |
| — | [OUT_OF_SCOPE.md](OUT_OF_SCOPE.md) | Что НЕ делаем и почему | — | — |

## Рекомендуемый порядок

```
ETAP_1 → ETAP_5 → ETAP_2 → ETAP_3 → ETAP_4 → ETAP_6
```

- **1 → 5**: безопасные правки (логи/ProGuard/конфиги, потом data-слой — оба низкорисковые).
- **2**: критические, может ломать поведение sync — сделать после стабилизации остальных слоёв.
- **3**: завязан на изменения координатора в 2.
- **4**: независимый UI-этап.
- **6**: финальные правки ядра, после стабилизации.

## Acceptance criteria для всего пакета

После всех 6 этапов должно быть:

- [x] **Нет утечки токенов в logcat** (`HttpLoggingInterceptor.Level.HEADERS` + `redactHeader("Authorization")`).
- [x] **Release с отсутствующим keystore падает** на этапе сборки, не подписывается debug-ключом.
- [x] **Backup облака не содержит `datastore/`** (или `allowBackup="false"`).
- [x] **Logout (Auth, Settings) делает полную очистку**: push unregister + sync state + repository (через `LogoutCoordinator`).
- [ ] **`recordLivePoll`/`recordFullLiveSync`** срабатывают **после** merge + save.
- [ ] **`ExistingWorkPolicy.KEEP`** для всех unique sync/keepalive workers.
- [ ] **`LiveApiRefreshWorker`** всегда планирует следующий запуск даже при exception (`try/finally`).
- [x] **`POST_NOTIFICATIONS` denied** → dedupe-mark не ставится, push догонит после выдачи permission.
- [x] **`collectDueForReminder`** работает на «минутах до начала», а не на абсолютном времени напоминания.
- [ ] **WebView destroyed** в `DisposableEffect.onDispose`.
- [ ] **Sync VM** игнорирует повторные вызовы, пока `isLoading == true`.
- [x] **HTTP 401** → автоматический `tokenStorage.clear()` + сообщение «Сессия истекла».
- [x] **`MIN_NAME_MATCH_SCORE = 2`** или объяснённый комментарий, почему 1.
- [ ] **Async init** в `NeiroApplication.onCreate`.
- [ ] **Lints без новых ошибок** в IDE (`ReadLints` чистый).

## Что НЕ менять в поведении

- **`isInCurrentMonth` authoritative wipe в `YClientsCalendarSync`** — текущий месяц перезаписывается из API; локальные ученики/диагностика без записи в YClients удаляются. Это by design.
- **`LaunchedEffect(initialNames, isPlanningMode)` в `DayDetailsDialog`** — затерание локальных правок при фоновом sync; **не добавлять флаг `userEdited`/diff-merge**.
- **Архивный календарь (`saveDayToArchive`, `deleteDayFromArchive`)** — поведение записи и чтения сохраняется как есть. Не менять `saveDayToArchive` на чтение из DataStore вместо `cachedState`, **не добавлять `savedDayJson` в `writeSyncCache`**. (Архив уже корректно работает в текущем поведении пользователя — после `warmUp` всё ОК; cold-start редкая ситуация, пользователь явно сказал «архив трогать при обновлении не нужно».)
- **`hashCode()` как `notification ID`** — заменяем на стабильный hash без изменения семантики уведомлений.

## Правила коммитов

См. `.cursorrules` и `.cursor/rules/git-commits-ru.mdc`. В каждом этапе **уже даны заготовки** русских коммит-сообщений в духе:

- `Исправил утечку токена в HTTP-логах`
- `Перенёс recordLivePoll после merge`
- `Сделал logout единой точкой LogoutCoordinator`

Не объединять разнородные правки в один коммит. Если этап содержит 5 разных правок — 5 коммитов.

## Глоссарий

- **DataStore** — Jetpack DataStore, основное хранилище календаря и профиля.
- **TokenStorage** — `EncryptedSharedPreferences` для YClients-токенов.
- **WorkManager** — фоновые задачи (`LiveApiRefreshWorker`, `PushSyncWorker`, `PushKeepAliveWorker`, `SessionReminderWorker`, etc.).
- **LiveApi** — incremental polling YClients с `changed_after`.
- **PushConfig.isActive** — true, если есть `google-services.json` и backend URL/key настроены.
- **Архив (saved/personal)** — локальный календарь пользователя (`CalendarMode.PERSONAL`).
- **Sync календарь (main)** — данные из YClients (`CalendarMode.SYNCED`).
