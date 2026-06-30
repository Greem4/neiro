package ru.greemlab.neiro.notifications

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalDate

/**
 * Настройки и состояние уведомлений о занятиях.
 */
class SessionNotificationPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var notifyNewBooking: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_NEW, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_NEW, value).apply()

    var notifyCancelled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_CANCELLED, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_CANCELLED, value).apply()

    var notifyRescheduled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_RESCHEDULED, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_RESCHEDULED, value).apply()

    var notifyDeleted: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_DELETED, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_DELETED, value).apply()

    var notifyClientConfirmed: Boolean
        get() = readLegacyAware(KEY_NOTIFY_CONFIRMED, KEY_NOTIFY_STATUS)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_CONFIRMED, value).apply()

    var notifyClientArrived: Boolean
        get() = readLegacyAware(KEY_NOTIFY_ARRIVED, KEY_NOTIFY_STATUS)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_ARRIVED, value).apply()

    private fun readLegacyAware(key: String, legacyKey: String): Boolean {
        if (prefs.contains(key)) return prefs.getBoolean(key, true)
        if (prefs.contains(legacyKey)) return prefs.getBoolean(legacyKey, true)
        return true
    }

    var notifyReminder: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_REMINDER, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_REMINDER, value).apply()

    var notifyTodayDigest: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_DIGEST, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_DIGEST, value).apply()

    var notifyTomorrowDigest: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_TOMORROW_DIGEST, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_TOMORROW_DIGEST, value).apply()

    var notifyArchiveReminder: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_ARCHIVE_REMINDER, true)
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
        prefs.getStringSet(KEY_NOTIFIED_EVENT_KEYS, emptySet()).orEmpty().contains(dedupeKey)

    fun markEventNotified(dedupeKey: String) {
        val updated = prefs.getStringSet(KEY_NOTIFIED_EVENT_KEYS, emptySet()).orEmpty().toMutableSet()
        updated.add(dedupeKey)
        if (updated.size > MAX_NOTIFIED_KEYS) updated.remove(updated.first())
        prefs.edit().putStringSet(KEY_NOTIFIED_EVENT_KEYS, updated).apply()
    }

    fun wasReminderNotified(dedupeKey: String): Boolean =
        prefs.getStringSet(KEY_NOTIFIED_REMINDER_KEYS, emptySet()).orEmpty().contains(dedupeKey)

    fun markReminderNotified(dedupeKey: String) {
        val updated = prefs.getStringSet(KEY_NOTIFIED_REMINDER_KEYS, emptySet()).orEmpty().toMutableSet()
        updated.add(dedupeKey)
        if (updated.size > MAX_NOTIFIED_KEYS) updated.remove(updated.first())
        prefs.edit().putStringSet(KEY_NOTIFIED_REMINDER_KEYS, updated).apply()
    }

    fun clearNotifiedKeys() {
        prefs.edit()
            .remove(KEY_NOTIFIED_EVENT_KEYS)
            .remove(KEY_NOTIFIED_REMINDER_KEYS)
            .apply()
    }

    /** Сброс снимка календаря и dedupe — для повторной симуляции синка в debug. */
    fun resetSyncNotificationState() {
        prefs.edit()
            .remove(KEY_SNAPSHOT)
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
        prefs.getStringSet(KEY_ARCHIVE_REMINDER_DAYS, emptySet()).orEmpty()
            .contains(pastDayEpochDay.toString())

    fun markArchiveReminderShown(pastDayEpochDay: Long) {
        val updated = prefs.getStringSet(KEY_ARCHIVE_REMINDER_DAYS, emptySet()).orEmpty().toMutableSet()
        updated.add(pastDayEpochDay.toString())
        if (updated.size > MAX_NOTIFIED_KEYS) updated.remove(updated.first())
        prefs.edit().putStringSet(KEY_ARCHIVE_REMINDER_DAYS, updated).apply()
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
        val updated = prefs.getStringSet(KEY_ARCHIVE_REMINDER_DAYS, emptySet()).orEmpty().toMutableSet()
        updated.add(pastDayEpochDay.toString())
        if (updated.size > MAX_NOTIFIED_KEYS) updated.remove(updated.first())
        return prefs.edit().putStringSet(KEY_ARCHIVE_REMINDER_DAYS, updated).commit()
    }

    fun clearArchiveReminderShown(epochDay: Long = LocalDate.now().toEpochDay()) {
        val updated = prefs.getStringSet(KEY_ARCHIVE_REMINDER_DAYS, emptySet()).orEmpty().toMutableSet()
        updated.remove(epochDay.toString())
        prefs.edit().putStringSet(KEY_ARCHIVE_REMINDER_DAYS, updated).apply()
    }

    fun saveSnapshot(sessions: List<TrackedSession>) {
        val json = gson.toJson(sessions.map { it.toSnapshotDto() })
        prefs.edit()
            .putString(KEY_SNAPSHOT, json)
            .apply()
        hasBaselineSnapshot = true
    }

    fun loadSnapshot(): List<TrackedSession> {
        val json = prefs.getString(KEY_SNAPSHOT, null) ?: return emptyList()
        val type = object : TypeToken<List<SnapshotDto>>() {}.type
        return runCatching {
            gson.fromJson<List<SnapshotDto>>(json, type).map { it.toTracked() }
        }.getOrElse { emptyList() }
    }

    fun establishBaseline(sessions: List<TrackedSession>) {
        saveSnapshot(sessions)
        hasBaselineSnapshot = true
    }

    private data class SnapshotDto(
        val date: String,
        val startTime: String,
        val endTime: String,
        val clientName: String,
        val kind: String,
        val statusCode: Int,
        val isMarkedDeleted: Boolean,
    )

    private fun TrackedSession.toSnapshotDto() = SnapshotDto(
        date = date.toString(),
        startTime = startTime.toString(),
        endTime = endTime.toString(),
        clientName = clientName,
        kind = kind.name,
        statusCode = status.code,
        isMarkedDeleted = isMarkedDeleted,
    )

    private fun SnapshotDto.toTracked() = TrackedSession(
        date = LocalDate.parse(date),
        startTime = java.time.LocalTime.parse(startTime),
        endTime = java.time.LocalTime.parse(endTime),
        clientName = clientName,
        kind = UpcomingSessionKind.valueOf(kind),
        status = ru.greemlab.neiro.ui.calendar.AttendanceStatus.fromCode(statusCode),
        isMarkedDeleted = isMarkedDeleted,
    )

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
        private const val KEY_TODAY_DIGEST_DAY = "today_digest_epoch_day"
        private const val KEY_TOMORROW_DIGEST_TARGET_DAY = "tomorrow_digest_target_epoch_day"
        private const val KEY_ARCHIVE_REMINDER_DAYS = "archive_reminder_epoch_days"
        private const val KEY_SNAPSHOT = "calendar_snapshot"
        private const val KEY_HAS_BASELINE = "has_baseline_snapshot"
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
