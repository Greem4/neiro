package ru.greemlab.neiro.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import ru.greemlab.neiro.data.network.YClientsRepository

/**
 * Фоновая подтяжка записей с YClients; после выполнения планирует следующий запуск
 * с интервалом по [LiveApiPollSchedule].
 *
 * Гарантирует, что следующий запуск планируется даже при exception — иначе
 * цепочка обновлений ломается до перезапуска приложения.
 */
class LiveApiRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!YClientsRepository.getInstance(applicationContext).isLoggedIn.first()) {
            return Result.success()
        }

        val outcome = runCatching {
            YClientsCalendarSync.get(applicationContext).refreshLiveRange()
        }

        try {
            return when {
                outcome.isFailure -> {
                    Log.w(TAG, "refreshLiveRange threw", outcome.exceptionOrNull())
                    Result.retry()
                }
                outcome.getOrNull() is SyncOutcome.Failure -> {
                    Result.retry()
                }
                else -> Result.success()
            }
        } finally {
            LiveApiCoordinator.scheduleNextBackgroundRefresh(applicationContext)
        }
    }

    private companion object {
        const val TAG = "LiveApiRefreshWorker"
    }
}
