package ru.greemlab.neiro.push

import android.content.Context

/**
 * Курсор `last_event_id` сервиса пуш-событий — общий для показа push'а и догона (§6).
 */
object PushEventsCursor {
    private const val PREFS = "neiro_push_registrar"
    private const val KEY_LAST_EVENT_ID = "last_event_id"

    fun get(context: Context): Long =
        prefs(context).getLong(KEY_LAST_EVENT_ID, 0L)

    /** Максимумом с уже сохранённым — push'и могут прийти не по порядку. */
    fun markSeen(context: Context, eventId: Long) {
        if (eventId > get(context)) {
            prefs(context).edit().putLong(KEY_LAST_EVENT_ID, eventId).apply()
        }
    }

    /** Курсор ещё не задан (новое устройство) — принять начальное значение из ответа входа. */
    fun setIfAbsent(context: Context, eventId: Long) {
        if (get(context) == 0L) {
            prefs(context).edit().putLong(KEY_LAST_EVENT_ID, eventId).apply()
        }
    }

    fun reset(context: Context) {
        prefs(context).edit().remove(KEY_LAST_EVENT_ID).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
