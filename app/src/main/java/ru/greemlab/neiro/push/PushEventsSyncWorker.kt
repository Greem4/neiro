package ru.greemlab.neiro.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/**
 * Догон по нуджу сервера (`action = sync_events`): FCM-сервис не подходит для
 * сетевого запроса, поэтому нудж только ставит эту работу в очередь.
 */
class PushEventsSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        runCatching { PushEventsSyncer.syncNow(applicationContext) }
            // Отмену не глушим: иначе Result.retry() воскресил бы работу,
            // остановленную логаутом или системой.
            .onFailure { if (it is CancellationException) throw it }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
}
