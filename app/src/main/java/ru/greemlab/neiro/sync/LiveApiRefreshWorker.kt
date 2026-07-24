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

        if (outcome.isFailure) {
            Log.w(TAG, "refreshLiveRange threw", outcome.exceptionOrNull())
            return Result.retry()
        }
        if (outcome.getOrNull() is SyncOutcome.Failure) {
            return Result.retry()
        }

        if (!isStopped) {
            LiveApiCoordinator.scheduleNextBackgroundRefresh(applicationContext)
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "LiveApiRefreshWorker"
    }
}
