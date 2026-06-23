package ru.greemlab.neiro.sync

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Настройки и метаданные автоматической синхронизации YClients.
 */
class SyncPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isAutoSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SYNC, value).apply()

    fun lastSyncEpochMillis(): Long = prefs.getLong(KEY_LAST_SYNC_EPOCH, 0L)

    fun lastSyncLocalDate(): LocalDate? {
        val epoch = lastSyncEpochMillis()
        if (epoch <= 0L) return null
        return Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    fun recordSuccessfulSync(atMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_SYNC_EPOCH, atMillis).apply()
    }

    fun clearLastSync() {
        prefs.edit().remove(KEY_LAST_SYNC_EPOCH).apply()
    }

    /** Полная загрузка истории с YClients уже выполнялась (повтор — только вручную). */
    var hasCompletedInitialFullSync: Boolean
        get() = prefs.getBoolean(KEY_INITIAL_FULL_SYNC_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_INITIAL_FULL_SYNC_DONE, value).apply()

    fun markInitialFullSyncComplete() {
        hasCompletedInitialFullSync = true
    }

    fun clearSyncState() {
        prefs.edit()
            .remove(KEY_LAST_SYNC_EPOCH)
            .remove(KEY_INITIAL_FULL_SYNC_DONE)
            .remove(KEY_LAST_LIVE_POLL_EPOCH)
            .remove(KEY_LAST_FULL_LIVE_SYNC_EPOCH)
            .apply()
    }

    /** Метка последнего live-опроса (инкремент или полный). */
    fun lastLivePollEpochMillis(): Long = prefs.getLong(KEY_LAST_LIVE_POLL_EPOCH, 0L)

    fun recordLivePoll(atMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_LIVE_POLL_EPOCH, atMillis).apply()
    }

    /** Метка последней полной подтяжки диапазона live-опроса (страховка от «призраков»). */
    fun lastFullLiveSyncEpochMillis(): Long = prefs.getLong(KEY_LAST_FULL_LIVE_SYNC_EPOCH, 0L)

    fun recordFullLiveSync(atMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_FULL_LIVE_SYNC_EPOCH, atMillis).apply()
    }

    fun clearLivePollState() {
        prefs.edit()
            .remove(KEY_LAST_LIVE_POLL_EPOCH)
            .remove(KEY_LAST_FULL_LIVE_SYNC_EPOCH)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "neiro_sync_prefs"
        private const val KEY_AUTO_SYNC = "auto_sync_enabled"
        private const val KEY_LAST_SYNC_EPOCH = "last_sync_epoch"
        private const val KEY_INITIAL_FULL_SYNC_DONE = "initial_full_sync_done"
        private const val KEY_LAST_LIVE_POLL_EPOCH = "last_live_poll_epoch"
        private const val KEY_LAST_FULL_LIVE_SYNC_EPOCH = "last_full_live_sync_epoch"

        @Volatile
        private var instance: SyncPreferences? = null

        fun get(context: Context): SyncPreferences =
            instance ?: synchronized(this) {
                instance ?: SyncPreferences(context).also { instance = it }
            }
    }
}
