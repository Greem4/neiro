package ru.greemlab.neiro.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ru.greemlab.neiro.data.network.YClientsRepository

/**
 * Фоновая подтяжка записей с YClients; после выполнения планирует следующий запуск
 * с интервалом по [LiveApiPollSchedule].
 */
class LiveApiRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!YClientsRepository.getInstance(applicationContext).isLoggedIn.value) {
            return Result.success()
        }

        YClientsCalendarSync.get(applicationContext).refreshLiveRange()
        LiveApiCoordinator.scheduleNextBackgroundRefresh(applicationContext)
        return Result.success()
    }
}
