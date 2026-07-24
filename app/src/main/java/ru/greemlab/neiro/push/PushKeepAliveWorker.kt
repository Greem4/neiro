package ru.greemlab.neiro.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import ru.greemlab.neiro.data.network.YClientsRepository
import ru.greemlab.neiro.sync.YClientsCalendarSync

/**
 * Периодически обновляет регистрацию на push-сервере и подтягивает календарь,
 * если FCM не дошёл (Doze, экономия батареи и т.п.).
 */
class PushKeepAliveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!PushConfig.isActive) return Result.success()

        val repository = YClientsRepository.getInstance(applicationContext)
        if (!repository.isLoggedIn.first()) return Result.success()

        try {
            runCatching { PushRegistrar.registerNow(applicationContext) }
                .onFailure { if (it is CancellationException) throw it }
            runCatching {
                YClientsCalendarSync.get(applicationContext).refreshLiveRange()
            }.onFailure { if (it is CancellationException) throw it }
        } finally {
            if (!isStopped) {
                PushKeepAliveCoordinator.scheduleNext(applicationContext)
            }
        }
        return Result.success()
    }
}
