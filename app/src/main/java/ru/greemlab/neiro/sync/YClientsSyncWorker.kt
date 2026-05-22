package ru.greemlab.neiro.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ru.greemlab.neiro.data.network.YClientsRepository

/**
 * Периодическая фоновая синхронизация записей YClients.
 */
class YClientsSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val syncPreferences = SyncPreferences.get(applicationContext)
        if (!syncPreferences.isAutoSyncEnabled) return Result.success()

        val yclientsRepository = YClientsRepository.getInstance(applicationContext)
        if (!yclientsRepository.isLoggedIn.value) return Result.success()

        when (YClientsCalendarSync.get(applicationContext).syncDefaultAutoRange()) {
            is SyncOutcome.Success -> Unit
            is SyncOutcome.Failure -> Unit
        }
        return Result.success()
    }
}
