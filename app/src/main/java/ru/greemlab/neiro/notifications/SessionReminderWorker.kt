package ru.greemlab.neiro.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Показывает напоминание о занятии в запланированное время (one-time work
 * с dedupe-ключом). Общая логика — в [SessionNotificationCoordinator.runReminderCheck].
 */
class SessionReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        SessionNotificationCoordinator.runReminderCheck(
            applicationContext,
            inputData.getString(SessionNotificationCoordinator.KEY_DEDUPE),
        )
        return Result.success()
    }
}
