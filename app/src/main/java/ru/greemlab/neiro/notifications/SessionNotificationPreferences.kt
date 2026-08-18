package ru.greemlab.neiro.notifications

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * Настройки и состояние уведомлений о занятиях.
 */
class SessionNotificationPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Единый источник master-тумблера: [get] — синглтон (companion), поэтому этот
    // StateFlow общий для всех экранов/ViewModel — без него AppSettingsViewModel
    // (activity-scoped) и SessionNotificationSettingsViewModel независимо кэшировали
    // isEnabled в своих StateFlow, и смена тумблера на одном экране не была видна
    // на другом до пересоздания ViewModel (P3).
    private val _isEnabledFlow = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED))
    val isEnabledFlow: StateFlow<Boolean> = _isEnabledFlow.asStateFlow()

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            _isEnabledFlow.value = value
        }

    var notifyNewBooking: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_NEW, DEFAULT_NOTIFY_NEW)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_NEW, value).apply()

    var notifyCancelled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_CANCELLED, DEFAULT_NOTIFY_CANCELLED)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_CANCELLED, value).apply()

    var notifyRescheduled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_RESCHEDULED, DEFAULT_NOTIFY_RESCHEDULED)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_RESCHEDULED, value).apply()

    var notifyDeleted: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_DELETED, DEFAULT_NOTIFY_DELETED)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_DELETED, value).apply()

    var notifyClientConfirmed: Boolean
        get() = readLegacyAware(KEY_NOTIFY_CONFIRMED, KEY_NOTIFY_STATUS, DEFAULT_NOTIFY_CONFIRMED)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_CONFIRMED, value).apply()

    var notifyClientArrived: Boolean
        get() = readLegacyAware(KEY_NOTIFY_ARRIVED, KEY_NOTIFY_STATUS, DEFAULT_NOTIFY_ARRIVED)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_ARRIVED, value).apply()

    /**
     * Умолчание применяется, только когда нет ни нового ключа, ни старого
     * общего `notify_status`: у того, кто когда-то настраивал статусы, остаётся
     * его выбор.
     */
    private fun readLegacyAware(key: String, legacyKey: String, default: Boolean): Boolean {
        if (prefs.contains(key)) return prefs.getBoolean(key, default)
        if (prefs.contains(legacyKey)) return prefs.getBoolean(legacyKey, default)
        return default
    }

    var notifyReminder: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_REMINDER, DEFAULT_NOTIFY_REMINDER)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_REMINDER, value).apply()

    var notifyTodayDigest: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_DIGEST, DEFAULT_NOTIFY_TODAY_DIGEST)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_DIGEST, value).apply()

    var notifyTomorrowDigest: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_TOMORROW_DIGEST, DEFAULT_NOTIFY_TOMORROW_DIGEST)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_TOMORROW_DIGEST, value).apply()

    var notifyArchiveReminder: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_ARCHIVE_REMINDER, DEFAULT_NOTIFY_ARCHIVE_REMINDER)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_ARCHIVE_REMINDER, value).apply()

    /** За сколько минут до начала напомнить. */
    var reminderMinutesBefore: Int
        get() = prefs.getInt(KEY_REMINDER_MINUTES, DEFAULT_REMINDER_MINUTES)
        set(value) = prefs.edit().putInt(KEY_REMINDER_MINUTES, value.coerceIn(5, 120)).apply()

    var todayDigestTime: ScheduledNotificationTime
        get() = ScheduledNotificationTime.fromMinutesFromMidnight(
            prefs.getInt(KEY_TODAY_DIGEST_TIME, DEFAULT_TODAY_DIGEST_TIME_MINUTES),
        )
        set(value) = prefs.edit().putInt(KEY_TODAY_DIGEST_TIME, value.toMinutesFromMidnight()).apply()

    var tomorrowDigestTime: ScheduledNotificationTime
        get() = ScheduledNotificationTime.fromMinutesFromMidnight(
            prefs.getInt(KEY_TOMORROW_DIGEST_TIME, DEFAULT_TOMORROW_DIGEST_TIME_MINUTES),
        )
        set(value) = prefs.edit().putInt(KEY_TOMORROW_DIGEST_TIME, value.toMinutesFromMidnight()).apply()

    var archiveReminderTime: ScheduledNotificationTime
        get() = ScheduledNotificationTime.fromMinutesFromMidnight(
            prefs.getInt(KEY_ARCHIVE_REMINDER_TIME, DEFAULT_ARCHIVE_REMINDER_TIME_MINUTES),
        )
        set(value) = prefs.edit().putInt(KEY_ARCHIVE_REMINDER_TIME, value.toMinutesFromMidnight()).apply()

    var hasBaselineSnapshot: Boolean
        get() = prefs.getBoolean(KEY_HAS_BASELINE, false)
        private set(value) = prefs.edit().putBoolean(KEY_HAS_BASELINE, value).apply()

    fun isTypeEnabled(type: SessionEventType): Boolean = when (type) {
        SessionEventType.NEW_BOOKING -> notifyNewBooking
        SessionEventType.CANCELLED -> notifyCancelled
        SessionEventType.RESCHEDULED -> notifyRescheduled
        SessionEventType.DELETED -> notifyDeleted
        SessionEventType.CLIENT_CONFIRMED -> notifyClientConfirmed
        SessionEventType.CLIENT_ARRIVED -> notifyClientArrived
        SessionEventType.REMINDER -> notifyReminder
        SessionEventType.TODAY_DIGEST -> notifyTodayDigest
        SessionEventType.TOMORROW_DIGEST -> notifyTomorrowDigest
        SessionEventType.ARCHIVE_REMINDER -> notifyArchiveReminder
    }

    fun wasEventNotified(dedupeKey: String): Boolean =
        readOrderedKeys(KEY_NOTIFIED_EVENT_KEYS_LIST, legacy = KEY_NOTIFIED_EVENT_KEYS).contains(dedupeKey)

    @Synchronized
    fun markEventNotified(dedupeKey: String) {
        val updated = readOrderedKeys(KEY_NOTIFIED_EVENT_KEYS_LIST, legacy = KEY_NOTIFIED_EVENT_KEYS)
        updated.remove(dedupeKey)
        updated.add(dedupeKey)
        while (updated.size > MAX_NOTIFIED_KEYS) {
            val first = updated.iterator().next()
            updated.remove(first)
        }
        prefs.edit().putString(KEY_NOTIFIED_EVENT_KEYS_LIST, updated.joinToString(SEPARATOR)).apply()
    }

    fun wasReminderNotified(dedupeKey: String): Boolean =
        readOrderedKeys(KEY_NOTIFIED_REMINDER_KEYS_LIST, legacy = KEY_NOTIFIED_REMINDER_KEYS).contains(dedupeKey)

    @Synchronized
    fun markReminderNotified(dedupeKey: String) {
        val updated = readOrderedKeys(KEY_NOTIFIED_REMINDER_KEYS_LIST, legacy = KEY_NOTIFIED_REMINDER_KEYS)
        updated.remove(dedupeKey)
        updated.add(dedupeKey)
        while (updated.size > MAX_NOTIFIED_KEYS) {
            val first = updated.iterator().next()
            updated.remove(first)
        }
        prefs.edit().putString(KEY_NOTIFIED_REMINDER_KEYS_LIST, updated.joinToString(SEPARATOR)).apply()
    }

    fun clearNotifiedKeys() {
        prefs.edit()
            .remove(KEY_NOTIFIED_EVENT_KEYS)
            .remove(KEY_NOTIFIED_REMINDER_KEYS)
            .remove(KEY_NOTIFIED_EVENT_KEYS_LIST)
            .remove(KEY_NOTIFIED_REMINDER_KEYS_LIST)
            .apply()
    }

    /** Сброс baseline и dedupe — для повторной симуляции синка в debug. */
    fun resetSyncNotificationState() {
        prefs.edit()
            .remove(KEY_HAS_BASELINE)
            .apply()
        clearNotifiedKeys()
    }

    fun lastTodayDigestEpochDay(): Long = prefs.getLong(KEY_TODAY_DIGEST_DAY, 0L)

    fun markTodayDigestShown(epochDay: Long) {
        prefs.edit().putLong(KEY_TODAY_DIGEST_DAY, epochDay).apply()
    }

    fun clearTodayDigestShown() {
        prefs.edit().remove(KEY_TODAY_DIGEST_DAY).apply()
    }

    /** День, на который уже показали сводку «завтра» (epoch day целевой даты). */
    fun lastTomorrowDigestTargetEpochDay(): Long = prefs.getLong(KEY_TOMORROW_DIGEST_TARGET_DAY, 0L)

    fun markTomorrowDigestShown(targetDayEpochDay: Long) {
        prefs.edit().putLong(KEY_TOMORROW_DIGEST_TARGET_DAY, targetDayEpochDay).apply()
    }

    fun clearTomorrowDigestShown() {
        prefs.edit().remove(KEY_TOMORROW_DIGEST_TARGET_DAY).apply()
    }

    fun wasArchiveReminderShown(pastDayEpochDay: Long): Boolean =
        readOrderedKeys(KEY_ARCHIVE_REMINDER_DAYS_LIST, legacy = KEY_ARCHIVE_REMINDER_DAYS)
            .contains(pastDayEpochDay.toString())

    @Synchronized
    fun markArchiveReminderShown(pastDayEpochDay: Long) {
        // Ordered-LRU как у notified_event_keys_v2: HashSet.first() удалял случайный ключ.
        val updated = readOrderedKeys(KEY_ARCHIVE_REMINDER_DAYS_LIST, legacy = KEY_ARCHIVE_REMINDER_DAYS)
        updated.remove(pastDayEpochDay.toString())
        updated.add(pastDayEpochDay.toString())
        while (updated.size > MAX_NOTIFIED_KEYS) {
            updated.remove(updated.iterator().next())
        }
        prefs.edit().putString(KEY_ARCHIVE_REMINDER_DAYS_LIST, updated.joinToString(SEPARATOR)).apply()
    }

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
        val updated = readOrderedKeys(KEY_ARCHIVE_REMINDER_DAYS_LIST, legacy = KEY_ARCHIVE_REMINDER_DAYS)
        updated.add(pastDayEpochDay.toString())
        while (updated.size > MAX_NOTIFIED_KEYS) {
            updated.remove(updated.iterator().next())
        }
        return prefs.edit().putString(KEY_ARCHIVE_REMINDER_DAYS_LIST, updated.joinToString(SEPARATOR)).commit()
    }

    @Synchronized
    fun clearArchiveReminderShown(epochDay: Long = LocalDate.now().toEpochDay()) {
        val updated = readOrderedKeys(KEY_ARCHIVE_REMINDER_DAYS_LIST, legacy = KEY_ARCHIVE_REMINDER_DAYS)
        updated.remove(epochDay.toString())
        prefs.edit().putString(KEY_ARCHIVE_REMINDER_DAYS_LIST, updated.joinToString(SEPARATOR)).apply()
    }

    /** Logout: полностью сбросить архивный LRU (иначе claim-и следующего аккаунта путаются со старыми). */
    fun clearAllArchiveReminders() {
        prefs.edit()
            .remove(KEY_ARCHIVE_REMINDER_DAYS_LIST)
            .remove(KEY_ARCHIVE_REMINDER_DAYS)
            .apply()
    }

    /** Снимок исторически сохранялся отдельно, но diff всегда считается по before/after
     *  в памяти (см. [SessionNotificationCoordinator]) — здесь нужен только факт baseline. */
    fun establishBaseline() {
        hasBaselineSnapshot = true
    }

    private fun readOrderedKeys(currentKey: String, legacy: String): java.util.LinkedHashSet<String> {
        val raw = prefs.getString(currentKey, null)
        if (raw != null) {
            return raw.split(SEPARATOR).filter { it.isNotEmpty() }.toCollection(java.util.LinkedHashSet())
        }
        val legacySet = prefs.getStringSet(legacy, emptySet()).orEmpty()
        return java.util.LinkedHashSet(legacySet)
    }

    companion object {
        private const val PREFS_NAME = "neiro_session_notifications"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_NOTIFY_NEW = "notify_new"
        private const val KEY_NOTIFY_CANCELLED = "notify_cancelled"
        private const val KEY_NOTIFY_RESCHEDULED = "notify_rescheduled"
        private const val KEY_NOTIFY_DELETED = "notify_deleted"
        private const val KEY_NOTIFY_STATUS = "notify_status"
        private const val KEY_NOTIFY_CONFIRMED = "notify_confirmed"
        private const val KEY_NOTIFY_ARRIVED = "notify_arrived"
        private const val KEY_NOTIFY_REMINDER = "notify_reminder"
        private const val KEY_NOTIFY_DIGEST = "notify_digest"
        private const val KEY_NOTIFY_TOMORROW_DIGEST = "notify_tomorrow_digest"
        private const val KEY_NOTIFY_ARCHIVE_REMINDER = "notify_archive_reminder"
        private const val KEY_REMINDER_MINUTES = "reminder_minutes"
        private const val KEY_TODAY_DIGEST_TIME = "today_digest_time_minutes"
        private const val KEY_TOMORROW_DIGEST_TIME = "tomorrow_digest_time_minutes"
        private const val KEY_ARCHIVE_REMINDER_TIME = "archive_reminder_time_minutes"
        private const val KEY_NOTIFIED_EVENT_KEYS = "notified_event_keys"
        private const val KEY_NOTIFIED_REMINDER_KEYS = "notified_reminder_keys"
        private const val KEY_NOTIFIED_EVENT_KEYS_LIST = "notified_event_keys_v2"
        private const val KEY_NOTIFIED_REMINDER_KEYS_LIST = "notified_reminder_keys_v2"
        private const val SEPARATOR = "\u0001"
        private const val KEY_TODAY_DIGEST_DAY = "today_digest_epoch_day"
        private const val KEY_TOMORROW_DIGEST_TARGET_DAY = "tomorrow_digest_target_epoch_day"
        private const val KEY_ARCHIVE_REMINDER_DAYS = "archive_reminder_epoch_days"
        private const val KEY_ARCHIVE_REMINDER_DAYS_LIST = "archive_reminder_epoch_days_v2"
        private const val KEY_HAS_BASELINE = "has_baseline_snapshot"

        // Умолчания. Достаются только тому, кто настройку ни разу не трогал:
        // сеттеры пишут в prefs по одному ключу, а записанное значение всегда
        // сильнее умолчания. Смена значений здесь новых предпочтений никому не
        // навязывает — она про чистую установку и про новый телефон.
        //
        // По умолчанию приходят все изменения в расписании, кроме отметки
        // «пришёл»: её ставят в YClients весь день, и это шум.
        //
        // Из расписания по умолчанию включено только напоминание об архиве:
        // без него занятия за прошлые дни так и остаются неперенесёнными.
        // Напоминание перед занятием и обе сводки выключены — они приходят
        // каждый день независимо от того, изменилось ли что-нибудь.
        private const val DEFAULT_ENABLED = true
        private const val DEFAULT_NOTIFY_NEW = true
        private const val DEFAULT_NOTIFY_CANCELLED = true
        private const val DEFAULT_NOTIFY_RESCHEDULED = true
        private const val DEFAULT_NOTIFY_DELETED = true
        private const val DEFAULT_NOTIFY_CONFIRMED = true
        private const val DEFAULT_NOTIFY_ARRIVED = false
        private const val DEFAULT_NOTIFY_REMINDER = false
        private const val DEFAULT_NOTIFY_TODAY_DIGEST = false
        private const val DEFAULT_NOTIFY_TOMORROW_DIGEST = false
        private const val DEFAULT_NOTIFY_ARCHIVE_REMINDER = true

        private const val DEFAULT_REMINDER_MINUTES = 30
        private const val DEFAULT_TODAY_DIGEST_TIME_MINUTES = 8 * 60
        private const val DEFAULT_TOMORROW_DIGEST_TIME_MINUTES = 20 * 60
        private const val DEFAULT_ARCHIVE_REMINDER_TIME_MINUTES = 21 * 60
        private const val MAX_NOTIFIED_KEYS = 300

        val REMINDER_MINUTE_OPTIONS = listOf(15, 30, 45, 60)

        @Volatile
        private var instance: SessionNotificationPreferences? = null

        fun get(context: Context): SessionNotificationPreferences =
            instance ?: synchronized(this) {
                instance ?: SessionNotificationPreferences(context).also { instance = it }
            }
    }
}
