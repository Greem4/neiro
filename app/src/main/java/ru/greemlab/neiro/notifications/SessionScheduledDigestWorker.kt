package ru.greemlab.neiro.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Показ сводки или напоминания об архиве в точное настроенное время.
 * После выполнения планирует следующий запуск на следующий день.
 */
class SessionScheduledDigestWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val kindName = inputData.getString(KEY_KIND) ?: return Result.failure()
        val kind = runCatching { ScheduledDigestKind.valueOf(kindName) }.getOrNull()
            ?: return Result.failure()

        try {
            SessionNotificationCoordinator.deliverScheduledDigest(applicationContext, kind)
        } finally {
            // isStopped: воркер отменён (например, пользователь только что сменил
            // время сводки) — не перепланировать поверх свежего enqueue из UI.
            if (!isStopped) {
                SessionNotificationCoordinator.rescheduleDigestFromWorker(applicationContext, kind)
            }
        }
        return Result.success()
    }

    companion object {
        const val KEY_KIND = "digest_kind"
    }
}
