package ru.greemlab.neiro.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import ru.greemlab.neiro.data.network.YClientsRepository

/**
 * Фоновая подтяжка записей с YClients; после успешного выполнения планирует
 * следующий запуск с интервалом по [LiveApiPollSchedule].
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
        }.onFailure { if (it is CancellationException) throw it }

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
            if (!isStopped) {
                LiveApiCoordinator.scheduleNextBackgroundRefresh(applicationContext)
            }
        }
    }

    private companion object {
        const val TAG = "LiveApiRefreshWorker"
    }
}
