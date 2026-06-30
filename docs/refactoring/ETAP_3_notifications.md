# Этап 3 — Notifications

**Уровень риска:** Средний. Меняем логику dedupe, окно reminder, claim digest — могут проявиться расхождения с предыдущим поведением.
**Зависимости:** Этап 2 (`LogoutCoordinator` должен существовать или быть закомментирован в `LogoutCoordinator.kt`).
**Acceptance:**
- [ ] `collectDueForReminder` использует «минуты до начала сессии», а не absolute `reminderAt`.
- [ ] `claimTodayDigest`/`claimTomorrowDigest`/`claimArchiveReminder` — атомарный compare-and-set через `@Synchronized` + `commit()`.
- [ ] Перед `prefs.markEventNotified` проверяется `areNotificationsEnabled()`.
- [ ] `notificationId` и `requestCode` — стабильный hash на основе `String.hashCode()`, не `Object.hashCode()` + xor.
- [ ] `InAppNotificationStore.append`, `ArchiveNotificationStore.append` thread-safe (`@Synchronized`).
- [ ] LRU для `notifiedEventKeys`/`notifiedReminderKeys` — `LinkedHashSet`, удаляется самый старый.
- [ ] `ScheduledNotificationTime` валидирует входные значения в `init`.
- [ ] `SessionNotificationCoordinator.onLoggedOut(context)` существует и используется в `LogoutCoordinator`.
- [ ] `enqueueDigestWork` использует `KEEP` (а `rescheduleDigest` явно `cancel` перед enqueue).

---

## Файлы для правки

1. `app/src/main/java/ru/greemlab/neiro/notifications/UpcomingSession.kt` — окно reminder.
2. `app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationPreferences.kt` — claim, LRU.
3. `app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationCoordinator.kt` — claim, KEEP, onLoggedOut.
4. `app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationDisplay.kt` — stable hash, permission check.
5. `app/src/main/java/ru/greemlab/neiro/notifications/InAppNotificationStore.kt` — `@Synchronized append`.
6. `app/src/main/java/ru/greemlab/neiro/notifications/ArchiveNotificationStore.kt` — `@Synchronized append`.
7. `app/src/main/java/ru/greemlab/neiro/notifications/ScheduledNotificationTime.kt` — валидация.

---

## 3.1 Reminder window: «минуты до начала», а не absolute `reminderAt`

**Файл:** `app/src/main/java/ru/greemlab/neiro/notifications/UpcomingSession.kt`
**Строки:** 82-94

### Проблема (критическая)

Текущая логика:

```kotlin
val windowStart = now.plusMinutes((reminderMinutesBefore - 7).toLong())  // now + 23
val windowEnd = now.plusMinutes((reminderMinutesBefore + 7).toLong())    // now + 37

return sessions.filter { session ->
    val reminderAt = session.reminderAt(reminderMinutesBefore)  // start - 30
    !reminderAt.isBefore(windowStart) && !reminderAt.isAfter(windowEnd)
}
```

Раскрываем условие: `now + 23 ≤ start - 30 ≤ now + 37`, т.е. **`now + 53 ≤ start ≤ now + 67`**.

С `reminderMinutesBefore = 30` напоминания приходят за **53–67 минут** до начала, а не за 23–37.

### Сейчас (82-94):

```82:94:app/src/main/java/ru/greemlab/neiro/notifications/UpcomingSession.kt
    fun collectDueForReminder(
        sessions: List<UpcomingSession>,
        reminderMinutesBefore: Int,
        now: LocalDateTime = LocalDateTime.now(),
    ): List<UpcomingSession> {
        val windowStart = now.plusMinutes((reminderMinutesBefore - 7).toLong())
        val windowEnd = now.plusMinutes((reminderMinutesBefore + 7).toLong())

        return sessions.filter { session ->
            val reminderAt = session.reminderAt(reminderMinutesBefore)
            !reminderAt.isBefore(windowStart) && !reminderAt.isAfter(windowEnd)
        }
    }
```

### Заменить на:

```kotlin
    fun collectDueForReminder(
        sessions: List<UpcomingSession>,
        reminderMinutesBefore: Int,
        now: LocalDateTime = LocalDateTime.now(),
    ): List<UpcomingSession> {
        val minMinutesUntilStart = (reminderMinutesBefore - REMINDER_WINDOW_HALF).toLong().coerceAtLeast(0)
        val maxMinutesUntilStart = (reminderMinutesBefore + REMINDER_WINDOW_HALF).toLong()

        return sessions.filter { session ->
            val minutesUntilStart = java.time.Duration.between(now, session.startsAt()).toMinutes()
            minutesUntilStart in minMinutesUntilStart..maxMinutesUntilStart
        }
    }

    private const val REMINDER_WINDOW_HALF = 7
```

**Почему:**
- Окно считается напрямую: «сколько минут осталось до начала сессии».
- При `reminderMinutesBefore = 30` ловим сессии, до начала которых осталось 23–37 минут.
- `coerceAtLeast(0)` — если `reminderMinutesBefore < 7` (например 5), окно начинается с 0 (сразу).

**Коммит:** `Исправил окно напоминаний по минутам до начала`

---

## 3.2 Atomic claim для digest

**Файл:** `app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationPreferences.kt`

### Проблема

`maybeShowTodayDigest` в `SessionNotificationCoordinator.kt:488-502`:

```kotlin
val epochDay = today.toEpochDay()
if (prefs.lastTodayDigestEpochDay() == epochDay) return  // CHECK
SessionNotificationDisplay.showTodayDigest(context, todayList)
prefs.markTodayDigestShown(epochDay)                      // SET
```

Между `CHECK` и `SET` другой воркер может пройти ту же проверку. Результат — дублирование push.

### Правка в `SessionNotificationPreferences.kt`

Добавить новые методы (после `markArchiveReminderShown`, ~строка 172):

```kotlin
    /**
     * Атомарный claim: возвращает true, если digest за этот день ещё не показан.
     * После true вызывающий обязан показать push. После false — пропустить.
     */
    @Synchronized
    fun claimTodayDigest(epochDay: Long): Boolean {
        if (prefs.getLong(KEY_TODAY_DIGEST_DAY, 0L) == epochDay) return false
        return prefs.edit().putLong(KEY_TODAY_DIGEST_DAY, epochDay).commit()
    }

    @Synchronized
    fun claimTomorrowDigest(targetEpochDay: Long): Boolean {
        if (prefs.getLong(KEY_TOMORROW_DIGEST_TARGET_DAY, 0L) == targetEpochDay) return false
        return prefs.edit().putLong(KEY_TOMORROW_DIGEST_TARGET_DAY, targetEpochDay).commit()
    }

    @Synchronized
    fun claimArchiveReminder(pastDayEpochDay: Long): Boolean {
        if (wasArchiveReminderShown(pastDayEpochDay)) return false
        val updated = prefs.getStringSet(KEY_ARCHIVE_REMINDER_DAYS, emptySet()).orEmpty().toMutableSet()
        updated.add(pastDayEpochDay.toString())
        if (updated.size > MAX_NOTIFIED_KEYS) updated.remove(updated.first())
        return prefs.edit().putStringSet(KEY_ARCHIVE_REMINDER_DAYS, updated).commit()
    }
```

> Существующие `markTodayDigestShown`/`markTomorrowDigestShown`/`markArchiveReminderShown` оставить (их использует `simulateSyncForDev` и можно потребоваться для debug).

### Правка в `SessionNotificationCoordinator.kt`

#### `maybeShowTodayDigest` (488-502):

```488:502:app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationCoordinator.kt
    private fun maybeShowTodayDigest(
        context: Context,
        upcoming: List<UpcomingSession>,
        prefs: SessionNotificationPreferences,
    ) {
        val today = LocalDate.now()
        val todayList = UpcomingSessionsCollector.todaySessions(upcoming, today)
        if (todayList.isEmpty()) return

        val epochDay = today.toEpochDay()
        if (prefs.lastTodayDigestEpochDay() == epochDay) return

        SessionNotificationDisplay.showTodayDigest(context, todayList)
        prefs.markTodayDigestShown(epochDay)
    }
```

**Заменить на:**

```kotlin
    private fun maybeShowTodayDigest(
        context: Context,
        upcoming: List<UpcomingSession>,
        prefs: SessionNotificationPreferences,
    ) {
        val today = LocalDate.now()
        val todayList = UpcomingSessionsCollector.todaySessions(upcoming, today)
        if (todayList.isEmpty()) return

        if (!prefs.claimTodayDigest(today.toEpochDay())) return

        SessionNotificationDisplay.showTodayDigest(context, todayList)
    }
```

#### `maybeShowTomorrowDigest` (504-519):

Аналогично:

```kotlin
    private fun maybeShowTomorrowDigest(
        context: Context,
        upcoming: List<UpcomingSession>,
        prefs: SessionNotificationPreferences,
    ) {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val tomorrowList = UpcomingSessionsCollector.tomorrowSessions(upcoming, today)
        if (tomorrowList.isEmpty()) return

        if (!prefs.claimTomorrowDigest(tomorrow.toEpochDay())) return

        SessionNotificationDisplay.showTomorrowDigest(context, tomorrowList)
    }
```

#### `maybeShowArchiveReminder` (521-539):

```kotlin
    private fun maybeShowArchiveReminder(
        context: Context,
        dayData: Map<LocalDate, List<String>>,
        savedDayData: Map<LocalDate, List<String>>,
        profile: UserProfile,
        prefs: SessionNotificationPreferences,
    ) {
        val today = LocalDate.now()
        val date = PastSessionsArchiveCollector.todayNeedingArchive(
            dayData = dayData,
            archivedDates = savedDayData.keys,
            profile = profile,
            today = today,
        ) ?: return
        if (!prefs.claimArchiveReminder(date.toEpochDay())) return

        SessionNotificationDisplay.showArchiveReminder(context, listOf(date), dayData)
    }
```

**Коммит:** `Сделал claim digest атомарным`

---

## 3.3 Проверка разрешения перед `markEventNotified`

**Файл:** `app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationCoordinator.kt`

### Проблема

`NotificationManagerCompat.notify` молча fail если `POST_NOTIFICATIONS` denied (Android 13+). Но `prefs.markEventNotified(it.dedupeKey)` ставит dedupe — следующий раз событие не покажется даже после выдачи permission.

### Сейчас (192-195, 132-136):

```192:195:app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationCoordinator.kt
        if (events.isNotEmpty()) {
            SessionNotificationDisplay.showEvents(context, events)
            events.forEach { prefs.markEventNotified(it.dedupeKey) }
        }
```

### Заменить (в `processSnapshotTransition` и `simulateSyncForDev` — оба места одинаковые):

```kotlin
        if (events.isNotEmpty()) {
            if (NotificationManagerCompat.from(context.applicationContext).areNotificationsEnabled()) {
                SessionNotificationDisplay.showEvents(context, events)
                events.forEach { prefs.markEventNotified(it.dedupeKey) }
            }
            // Если permission не выдан — НЕ марк, чтобы при следующем sync догнать.
        }
```

И добавить импорт:

```kotlin
import androidx.core.app.NotificationManagerCompat
```

Аналогично для reminder/digest в `SessionReminderWorker` и `SessionScheduledDigestWorker`:

```
rg 'prefs\.markReminderNotified|prefs\.markEventNotified' app/src/main
```

Везде, где `markEventNotified`/`markReminderNotified` вызывается **после** показа — обернуть в `if (areNotificationsEnabled())`.

> **Альтернатива:** проверка внутри `SessionNotificationDisplay.showEvents` с возвратом `Boolean` — но это меняет API. Делаем на месте вызова.

**Коммит:** `Не помечаю dedupe без разрешения на уведомления`

---

## 3.4 Stable hash для notification id и requestCode

**Файл:** `app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationDisplay.kt`

### Проблема

- `event.dedupeKey.hashCode()` (строки 166, 192, 218, 247) — `String.hashCode()` стабилен (JVM spec), это OK.
- `date.hashCode()` (строка 131) — `LocalDate.hashCode()` **не гарантирован** между версиями Java. На разных Android может различаться.
- `date.hashCode() xor (highlightSlotKey?.hashCode() ?: 0)` (строка 274) — два разных date с похожим slotKey могут дать одинаковый `requestCode`.

### Правки

#### 3.4.1 `openCalendarIntent` (264-281):

Сейчас:

```264:281:app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationDisplay.kt
    private fun openCalendarIntent(
        context: Context,
        date: LocalDate,
        highlightSlotKey: String? = null,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_DATE, date.toString())
            highlightSlotKey?.let { putExtra(MainActivity.EXTRA_HIGHLIGHT_SLOT_KEY, it) }
        }
        val requestCode = date.hashCode() xor (highlightSlotKey?.hashCode() ?: 0)
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
```

**Заменить на:**

```kotlin
    private fun openCalendarIntent(
        context: Context,
        date: LocalDate,
        highlightSlotKey: String? = null,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_DATE, date.toString())
            highlightSlotKey?.let { putExtra(MainActivity.EXTRA_HIGHLIGHT_SLOT_KEY, it) }
        }
        val requestCode = stableHash("${date}|${highlightSlotKey.orEmpty()}")
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stableHash(value: String): Int = value.hashCode()
```

#### 3.4.2 `showSingleArchiveReminder` (131):

Сейчас:

```kotlin
        notify(context, NOTIFICATION_ID_ARCHIVE_REMINDER + date.hashCode(), notification)
```

**Заменить на:**

```kotlin
        notify(context, NOTIFICATION_ID_ARCHIVE_REMINDER + stableHash(date.toString()), notification)
```

> `String.hashCode()` стабилен — `LocalDate.toString()` даёт `2026-06-30`.

**Коммит:** `Перешёл на стабильный hash для notification ID`

---

## 3.5 Thread-safe append в Store

**Файлы:**
- `app/src/main/java/ru/greemlab/neiro/notifications/InAppNotificationStore.kt`
- `app/src/main/java/ru/greemlab/neiro/notifications/ArchiveNotificationStore.kt`

### Проблема

`append`, `markAllRead`, `clearAll`, `remove` читают `_items.value`, потом пишут. При параллельных вызовах последний выигрывает, промежуточные теряются.

### 3.5.1 InAppNotificationStore

В `app/src/main/java/ru/greemlab/neiro/notifications/InAppNotificationStore.kt:41-77` сделать **все мутирующие функции `@Synchronized`**:

```kotlin
    @Synchronized
    fun append(
        title: String,
        body: String,
        relatedDate: LocalDate? = null,
        dedupeKey: String? = null,
        highlightSlotKey: String? = null,
        kind: SessionEventType? = null,
        timestampEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val current = _items.value
        if (dedupeKey != null && current.any { it.dedupeKey == dedupeKey }) return
        // ... остальное без изменений
    }

    @Synchronized
    fun markAllRead() {
        persist(_items.value.map { it.copy(read = true) })
    }

    @Synchronized
    fun clearAll() {
        persist(emptyList())
    }

    @Synchronized
    fun remove(id: String) {
        persist(_items.value.filter { it.id != id })
    }
```

### 3.5.2 ArchiveNotificationStore

Аналогично в `app/src/main/java/ru/greemlab/neiro/notifications/ArchiveNotificationStore.kt:41-87`:

```kotlin
    @Synchronized
    fun append(...) { ... }

    @Synchronized
    fun markAllRead() { ... }

    @Synchronized
    fun importJson(json: String): Boolean { ... }
```

> `exportJson()` не мутирует — `@Synchronized` не нужен (но допускается для консистентности чтения с записью).

**Почему:** Мьютекс на инстансе синглтона. Дёшево, безопасно.

**Коммит:** `Сделал append в Store thread-safe`

---

## 3.6 LRU для `notifiedEventKeys`/`notifiedReminderKeys`

**Файл:** `app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationPreferences.kt`
**Строки:** 109-114, 119-124

### Проблема

`prefs.getStringSet(...)` возвращает `Set<String>` — внутри `HashSet`. `updated.first()` для `HashSet` возвращает произвольный элемент (зависит от порядка bucket). Это значит, что при достижении `MAX_NOTIFIED_KEYS = 300` удаляется **случайный** элемент, не самый старый. В результате очень старые ключи могут жить вечно, а свежие — затираться.

### Правка

#### 3.6.1 markEventNotified (109-114):

```109:114:app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationPreferences.kt
    fun markEventNotified(dedupeKey: String) {
        val updated = prefs.getStringSet(KEY_NOTIFIED_EVENT_KEYS, emptySet()).orEmpty().toMutableSet()
        updated.add(dedupeKey)
        if (updated.size > MAX_NOTIFIED_KEYS) updated.remove(updated.first())
        prefs.edit().putStringSet(KEY_NOTIFIED_EVENT_KEYS, updated).apply()
    }
```

**Заменить на:**

```kotlin
    @Synchronized
    fun markEventNotified(dedupeKey: String) {
        val updated = readOrderedKeys(KEY_NOTIFIED_EVENT_KEYS_LIST, legacy = KEY_NOTIFIED_EVENT_KEYS)
        updated.remove(dedupeKey)
        updated.add(dedupeKey)
        while (updated.size > MAX_NOTIFIED_KEYS) updated.pollFirst()
        prefs.edit().putString(KEY_NOTIFIED_EVENT_KEYS_LIST, updated.joinToString(SEPARATOR)).apply()
    }
```

#### 3.6.2 wasEventNotified (106-107):

```kotlin
    fun wasEventNotified(dedupeKey: String): Boolean =
        readOrderedKeys(KEY_NOTIFIED_EVENT_KEYS_LIST, legacy = KEY_NOTIFIED_EVENT_KEYS).contains(dedupeKey)
```

#### 3.6.3 wasReminderNotified / markReminderNotified — то же:

```kotlin
    fun wasReminderNotified(dedupeKey: String): Boolean =
        readOrderedKeys(KEY_NOTIFIED_REMINDER_KEYS_LIST, legacy = KEY_NOTIFIED_REMINDER_KEYS).contains(dedupeKey)

    @Synchronized
    fun markReminderNotified(dedupeKey: String) {
        val updated = readOrderedKeys(KEY_NOTIFIED_REMINDER_KEYS_LIST, legacy = KEY_NOTIFIED_REMINDER_KEYS)
        updated.remove(dedupeKey)
        updated.add(dedupeKey)
        while (updated.size > MAX_NOTIFIED_KEYS) updated.pollFirst()
        prefs.edit().putString(KEY_NOTIFIED_REMINDER_KEYS_LIST, updated.joinToString(SEPARATOR)).apply()
    }
```

#### 3.6.4 clearNotifiedKeys (126-131):

```kotlin
    fun clearNotifiedKeys() {
        prefs.edit()
            .remove(KEY_NOTIFIED_EVENT_KEYS)
            .remove(KEY_NOTIFIED_REMINDER_KEYS)
            .remove(KEY_NOTIFIED_EVENT_KEYS_LIST)
            .remove(KEY_NOTIFIED_REMINDER_KEYS_LIST)
            .apply()
    }
```

#### 3.6.5 Helpers (приватные, в конце класса):

```kotlin
    private fun readOrderedKeys(currentKey: String, legacy: String): java.util.LinkedHashSet<String> {
        val raw = prefs.getString(currentKey, null)
        if (raw != null) {
            return raw.split(SEPARATOR).filter { it.isNotEmpty() }.toCollection(java.util.LinkedHashSet())
        }
        // миграция со старого StringSet (без порядка) — переносим как есть, без гарантий порядка
        val legacySet = prefs.getStringSet(legacy, emptySet()).orEmpty()
        return java.util.LinkedHashSet(legacySet)
    }
```

#### 3.6.6 Companion (добавить константы):

```kotlin
        private const val KEY_NOTIFIED_EVENT_KEYS_LIST = "notified_event_keys_v2"
        private const val KEY_NOTIFIED_REMINDER_KEYS_LIST = "notified_reminder_keys_v2"
        private const val SEPARATOR = "\u0001"
```

**Почему:**
- `LinkedHashSet` сохраняет insertion order: `pollFirst()` берёт реально самый старый.
- Старые `StringSet` остаются как fallback — при первом write мигрируем в новый ключ.
- `SEPARATOR = "\u0001"` — невидимый control character, не встретится в `dedupeKey`.

**Коммит:** `Сделал LRU очередь dedupe-ключей упорядоченной`

---

## 3.7 ScheduledNotificationTime: валидация в конструкторе

**Файл:** `app/src/main/java/ru/greemlab/neiro/notifications/ScheduledNotificationTime.kt`

### Проблема

`data class ScheduledNotificationTime(val hour: Int, val minute: Int)` принимает любые int. Если в prefs кривое значение (rare, но возможно: corrupted prefs, тест) → методы `coerceIn` маскируют ошибку. Лучше валидировать сразу.

### Правка (5-26):

```5:26:app/src/main/java/ru/greemlab/neiro/notifications/ScheduledNotificationTime.kt
data class ScheduledNotificationTime(
    val hour: Int,
    val minute: Int,
) {
    fun toLocalTime(): LocalTime = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))

    fun toMinutesFromMidnight(): Int = hour.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59)

    fun formatForDisplay(): String = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

    companion object {
        fun fromMinutesFromMidnight(minutes: Int): ScheduledNotificationTime {
            val total = minutes.coerceIn(0, 23 * 60 + 59)
            return ScheduledNotificationTime(total / 60, total % 60)
        }

        fun fromLocalTime(time: LocalTime): ScheduledNotificationTime =
            ScheduledNotificationTime(time.hour, time.minute)
    }
}
```

**Заменить на:**

```kotlin
data class ScheduledNotificationTime(
    val hour: Int,
    val minute: Int,
) {
    init {
        require(hour in 0..23) { "hour=$hour outside 0..23" }
        require(minute in 0..59) { "minute=$minute outside 0..59" }
    }

    fun toLocalTime(): LocalTime = LocalTime.of(hour, minute)

    fun toMinutesFromMidnight(): Int = hour * 60 + minute

    fun formatForDisplay(): String = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

    companion object {
        fun fromMinutesFromMidnight(minutes: Int): ScheduledNotificationTime {
            val total = minutes.coerceIn(0, 23 * 60 + 59)
            return ScheduledNotificationTime(total / 60, total % 60)
        }

        fun fromLocalTime(time: LocalTime): ScheduledNotificationTime =
            ScheduledNotificationTime(time.hour, time.minute)
    }
}
```

**Почему:** Инвариант теперь явно: конструктор отвергает невалидные значения. `fromMinutesFromMidnight` уже валидирует (`coerceIn`).

**Коммит:** `Добавил валидацию в ScheduledNotificationTime`

---

## 3.8 `onLoggedOut` для `LogoutCoordinator`

**Файл:** `app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationCoordinator.kt`

### Добавить в `object SessionNotificationCoordinator` (после `initialize`, ~строка 40):

```kotlin
    /**
     * Полная очистка состояния уведомлений при логауте YClients.
     * Не трогает архив и пользовательские настройки `prefs.isEnabled` / `notifyXxx`.
     */
    suspend fun onLoggedOut(context: Context) {
        val appContext = context.applicationContext
        cancelAll(appContext)
        SessionNotificationPreferences.get(appContext).resetSyncNotificationState()
        SessionNotificationPreferences.get(appContext).clearTodayDigestShown()
        SessionNotificationPreferences.get(appContext).clearTomorrowDigestShown()
    }
```

**Коммит:** `Добавил onLoggedOut в SessionNotificationCoordinator`

> После этого можно вернуть строку `SessionNotificationCoordinator.onLoggedOut(appContext)` в `LogoutCoordinator.logout` (если её закомментировали в Этапе 2).

---

## 3.9 `enqueueDigestWork` — `KEEP` вместо `REPLACE`

**Файл:** `app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationCoordinator.kt`
**Строки:** 350-371

### Проблема

`rescheduleAllDailyDigests` вызывается из `scheduleAfterBaseline` (после каждого FCM/sync). При `ExistingWorkPolicy.REPLACE` уже запланированный digest worker отменяется на каждом sync — digest не доставится, если sync произошёл в момент его срабатывания.

### Правка

#### 3.9.1 `enqueueDigestWork` (350-371) — `KEEP`:

```kotlin
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.KEEP, // было REPLACE
            request,
        )
```

#### 3.9.2 `rescheduleDigest` (251-282) — при смене настроек явно `cancel` перед enqueue:

```251:282:app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationCoordinator.kt
    fun rescheduleDigest(context: Context, kind: ScheduledDigestKind) {
        val appContext = context.applicationContext
        val prefs = SessionNotificationPreferences.get(appContext)
        if (!prefs.isEnabled) {
            cancelDigestWork(appContext, kind)
            return
        }

        when (kind) {
            ScheduledDigestKind.TODAY -> {
                if (!prefs.notifyTodayDigest) {
                    cancelDigestWork(appContext, kind)
                    return
                }
                enqueueDigestWork(appContext, WORK_TODAY_DIGEST, prefs.todayDigestTime, kind)
            }
            ...
        }
    }
```

**Заменить на (добавить cancel перед enqueue):**

```kotlin
    fun rescheduleDigest(context: Context, kind: ScheduledDigestKind) {
        val appContext = context.applicationContext
        val prefs = SessionNotificationPreferences.get(appContext)
        if (!prefs.isEnabled) {
            cancelDigestWork(appContext, kind)
            return
        }

        when (kind) {
            ScheduledDigestKind.TODAY -> {
                if (!prefs.notifyTodayDigest) {
                    cancelDigestWork(appContext, kind)
                    return
                }
                cancelDigestWork(appContext, kind) // принудительно сбрасываем старый
                enqueueDigestWork(appContext, WORK_TODAY_DIGEST, prefs.todayDigestTime, kind)
            }
            ScheduledDigestKind.TOMORROW -> {
                if (!prefs.notifyTomorrowDigest) {
                    cancelDigestWork(appContext, kind)
                    return
                }
                cancelDigestWork(appContext, kind)
                enqueueDigestWork(appContext, WORK_TOMORROW_DIGEST, prefs.tomorrowDigestTime, kind)
            }
            ScheduledDigestKind.ARCHIVE -> {
                if (!prefs.notifyArchiveReminder) {
                    cancelDigestWork(appContext, kind)
                    return
                }
                cancelDigestWork(appContext, kind)
                enqueueDigestWork(appContext, WORK_ARCHIVE_DIGEST, prefs.archiveReminderTime, kind)
            }
        }
    }
```

**Почему:**
- `rescheduleAllDailyDigests` (вызывается из sync) теперь идемпотентен: `KEEP` не отменяет уже запланированный.
- Прямой `rescheduleDigest` (из `onDigestTimeChanged`) явно делает `cancel` + enqueue — пересчёт времени гарантирован.

**Коммит:** `Сделал digest enqueue идемпотентным`

---

## 3.10 Удалить `prefs.markTodayDigestShown` после claim

**Файл:** `app/src/main/java/ru/greemlab/neiro/notifications/SessionNotificationCoordinator.kt`

После правки 3.2 `simulateSyncForDev` тоже использует `markEventNotified` (строка 134). Эту строку оставить — она нужна для debug-симуляции, где dedupe важен явно.

---

## Финальная проверка этапа

1. **`ReadLints`** для всех правленых файлов.
2. Поиск **legacy** dedupe методов: `markTodayDigestShown`, `markTomorrowDigestShown`:
   ```
   rg 'markTodayDigestShown|markTomorrowDigestShown' app/src/main
   ```
   Должны остаться **только** в `simulateSyncForDev` (debug) и в `clearTodayDigestShown`/`clearTomorrowDigestShown` (внутри Preferences). Если ещё где-то — заменить на `claimTodayDigest`/`claimTomorrowDigest`.
3. Поиск `date.hashCode()`:
   ```
   rg 'date\.hashCode\(\)|LocalDate.*hashCode' app/src/main
   ```
   Должно остаться **только** внутри `stableHash("$date|...")`.
4. Поиск `enqueueUniqueWork.*REPLACE` в `notifications/`:
   ```
   rg 'enqueueUniqueWork' app/src/main/java/ru/greemlab/neiro/notifications -A 2
   ```
   Для `rescheduleReminders` (рядом строка 480-484) `REPLACE` оставить — это работа с одной сессии, мы переписываем именно её планирование. Везде остальное должно быть `KEEP`.

## Коммиты этапа (порядок)

1. `Исправил окно напоминаний по минутам до начала`
2. `Сделал claim digest атомарным`
3. `Не помечаю dedupe без разрешения на уведомления`
4. `Перешёл на стабильный hash для notification ID`
5. `Сделал append в Store thread-safe`
6. `Сделал LRU очередь dedupe-ключей упорядоченной`
7. `Добавил валидацию в ScheduledNotificationTime`
8. `Добавил onLoggedOut в SessionNotificationCoordinator`
9. `Сделал digest enqueue идемпотентным`
