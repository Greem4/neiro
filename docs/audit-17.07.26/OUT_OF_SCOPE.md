# Out of Scope: что НЕ делаем

В аудите было найдено много проблем разной значимости. Здесь перечислены пункты, которые **намеренно не правим** в этом пакете — либо потому что это by design, либо потому что требует backend-изменений, либо потому что меняет UX, который пользователь хочет сохранить.

---

## 1. Поведение основного календаря (синхронизированного)

### 1.1 `isInCurrentMonth` authoritative wipe
**Файл:** `app/src/main/java/ru/greemlab/neiro/sync/YClientsCalendarSync.kt:298-309, 443-459, 808-812`

В `mergeRecordsToCalendar` для текущего месяца локальные ученики/диагностика без пары в YClients удаляются. Это by design: текущий месяц — авторитативный из API. **Не менять**.

### 1.2 `LaunchedEffect(initialNames)` в DayDetailsDialog
**Файл:** `app/src/main/java/ru/greemlab/neiro/ui/components/DayDetailsDialog.kt:190-208`

При фоновом sync открытый диалог редактирования сбрасывает локальные правки. Это by design: основной календарь — источник правды. Пользователь явно сказал не править поведение. **Не добавлять флаг `userEdited`, не делать diff-merge.**

---

## 2. Архивный календарь

### 2.1 `saveDayToArchive`/`deleteDayFromArchive` читают из `cachedState`
**Файл:** `app/src/main/java/ru/greemlab/neiro/data/CalendarDataStore.kt:223-243`

Аудит указал на риск перезаписи архива до `warmUp()`. Пользователь сказал: «архив трогать при обновлении не нужно». Текущее поведение работает в реальной эксплуатации (warmUp всегда успевает до пользовательских действий). **Не менять.**

### 2.2 `writeSyncCache` не пишет `saved_day_data`
**Файл:** `app/src/main/java/ru/greemlab/neiro/data/CalendarDataStore.kt:328-345`

Архив в sync-кэше не обновляется, чтобы не плодить дубликат «локальных и вечных» данных. **Не добавлять `savedDayJson` в writeSyncCache.**

---

## 3. Большие архитектурные рефакторинги

### 3.1 Миграция на Hilt/Koin (DI)
Все синглтоны (`YClientsRepository`, `CalendarDataStoreProvider`, `LiveApiCoordinator`, `PushRegistrar`, `SyncPreferences`, `TokenStorage`, etc.) остаются как есть. Migration слишком большая, не приоритет.

### 3.2 Перенос интерфейсов в `domain/`
`CalendarRepository` остаётся в `data/`. Clean architecture покрытие — отдельная задача.

### 3.3 Разделение god-composables
`CalendarScreen.kt` (1021 строк), `DayDetailsDialog.kt` (1010 строк), `ProfileContent.kt` (749 строк), `ProfileYearStatsSection.kt` (678 строк) — **не разбиваем**. Это требует серьёзного тестирования, в одной сессии безопасно не сделать.

### 3.4 Перенос `SessionParser` из `ui/calendar` в `domain`
Парсер на 500+ строк остаётся в UI-пакете. Перенос — отдельная задача.

### 3.5 Замена `InAppNotificationStore` / `ArchiveNotificationStore` на Room
SharedPreferences + Gson остаются. Полная перезапись JSON при каждом append — допустимый компромисс (300/5000 элементов максимум).

---

## 4. Изменения, требующие backend

### 4.1 Удаление YClients-токенов из push payload
**Файл:** `app/src/main/java/ru/greemlab/neiro/push/PushApi.kt:26-34`, `PushRegistrar.kt:97-106`

`partner_token`, `user_token`, `staff_id`, `company_id` отправляются на push-backend. Это плохо для безопасности, но **изменение требует переделки backend** (хранить opaque account_id, выписывать short-lived JWT). Не делаем в коде клиента.

### 4.2 `NEIRO_PUSH_API_KEY` в APK
Bearer-ключ в BuildConfig. Перейти на per-user JWT после YClients login требует backend. Не делаем.

### 4.3 `YCLIENTS_PARTNER_TOKEN` в APK
Это типовой компромисс YClients: partner token живёт на клиенте. Без proxy-backend убрать нельзя. Можно убрать `DEFAULT_COMPANY_ID = 520135` из дефолтов (это сделано в ETAP_5), но сам partner token остаётся.

---

## 5. Большие настройки, требующие тестирования

### 5.1 `AlarmManager.setExactAndAllowWhileIdle` для digest/reminder
WorkManager-задачи (15 мин periodic) на Android 12–15 в Doze задерживаются. Переход на exact alarms — отдельная задача с проверкой `canScheduleExactAlarms` на API 31+, runtime-разрешением и тестированием на нескольких устройствах. **Не делаем в этом пакете.**

### 5.2 `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` permissions
Не добавляем в манифест, т.к. не используем exact alarms.

### 5.3 BootReceiver — `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `QUICKBOOT_POWERON`
Дополнительные intent-filter требуют тестирования на OEM-устройствах (Xiaomi, HTC, Huawei). **Не делаем** — оставляем только `BOOT_COMPLETED`.

### 5.4 Compose BOM bump + foundation вне BOM
В ETAP_1 убираем явный pinning `foundation = 1.7.0`, тянем через BOM. Но **сам BOM не обновляем до новой версии** (требует regression-теста UI).

### 5.5 `security-crypto:1.1.0-alpha06` → stable
Не понижаем версию: в актуальном AndroidX `security-crypto` deprecated. Stable — это `1.0.0`, но он старый и не поддерживает новые `KeyScheme`. **Оставляем alpha06.** Если будет crash в release — добавим ProGuard keep отдельно.

---

## 6. Material 3 / Theme

### 6.1 Полная палитра M3
`Theme.kt:14-52` задаёт только часть токенов (`primary`, `secondary`, `background`, `surface`). M3 подставляет defaults для `surfaceVariant`/`outline`/`errorContainer` — на практике работает нормально. **Не заполняем полную палитру.**

### 6.2 Dynamic color
`dynamicColor = false` — сохраняем brand. Не включаем.

### 6.3 Surface вместо Box в MainActivity
`MainActivity.kt:150-154` — `Box` с `background` работает, перенос на `Surface`/`Scaffold` — косметика.

---

## 7. Hardcoded строки и Locale

### 7.1 Перенос hardcoded строк в `strings.xml`
В коде много hardcoded русских строк (`"Сегодня"`, `"Закрыть"`, `"Сохранить"`, тосты sync). Перенос — большая отдельная задача, **в этом пакете не делаем**. Локализация на en сейчас не используется.

### 7.2 `Locale("ru")` → `Locale.forLanguageTag("ru")`
Один-два места (`ProfileContent.kt:712-713`). Это deprecated, но работает. **Можно поправить, но не обязательно** (low priority).

---

## 8. Производительность мелкая

### 8.1 `CalendarGrid.kt:70-73` кэш `formatCalendarCounts`
Не делаем кэш — пересчёт быстрый, замеры производительности не показали проблем.

### 8.2 Полная перезапись JSON при каждом `append` в `InAppNotificationStore`/`ArchiveNotificationStore`
O(n) serialize + disk write на каждое уведомление. Лимит — 300/5000 элементов. На практике приемлемо.

### 8.3 `entries.map { TimelineEntry }` без `remember`
`DayDetailsDialog.kt:317-339`. На практике recomposition не зашкаливает — не критично.

---

## 9. Hardcoded дефолты профиля

### 9.1 `domain/models/UserProfile.kt:30-32`
`pricePerSession = 33_000`, `pricePerDiagnostics = 11_206`, `monthlyTaxAmount = 21_854` — это персональные дефолты владельца приложения. **Оставляем как есть** (приложение для одного пользователя в эксплуатации).

---

## 10. Прочие отдельные пункты

- **`HEADER_ICON_DP` bitmap как small icon** (`NeiroNotificationBranding.kt`) — на некоторых Android выглядит как серый квадрат. Замена на монохромный drawable — отдельная задача с дизайном.
- **`InAppNotificationRecorder` — двойная запись active + archive без транзакции**. Риск минимальный (crash между двумя SharedPreferences.apply() редок). Оставляем.
- ~~**`PastSessionsArchiveCollector.daysNeedingArchive` — мёртвый код для multi-day**.~~ Уже не мёртвый: используется для бейджа «забытые дни» на вкладке «Архив» и в вечернем напоминании (окно 30 дней).
- **`abortOnError = true` для release lint**. Может сломать сборку из-за warnings — отложим до отдельной задачи с baseline.
- **`SessionNotificationDevPreview` / `SessionNotificationSyncSimulation` за `BuildConfig.DEBUG`** — оставлено в ETAP_3, но не критично.
