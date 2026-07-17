package ru.greemlab.neiro.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import ru.greemlab.neiro.data.network.YClientsRepository
import ru.greemlab.neiro.sync.SyncOutcome
import ru.greemlab.neiro.sync.YClientsCalendarSync

/** Фоновая подтяжка календаря по FCM — не обрывается при завершении FCM-сервиса. */
class PushSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = YClientsRepository.getInstance(applicationContext)
        if (!repository.isLoggedIn.first()) return Result.success()

        return runCatching {
            when (YClientsCalendarSync.get(applicationContext).refreshLiveRange()) {
                is SyncOutcome.Failure -> Result.retry()
                else -> Result.success()
            }
        }.getOrElse { Result.retry() }
    }
}
