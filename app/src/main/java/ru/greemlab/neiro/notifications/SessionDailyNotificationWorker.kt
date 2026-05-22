package ru.greemlab.neiro.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.time.LocalTime

/**
 * Сводки и напоминание об архиве в настраиваемое время.
 */
class SessionDailyNotificationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        SessionNotificationCoordinator.runDailyScheduledChecks(applicationContext)
        return Result.success()
    }

    companion object {
        const val TIME_WINDOW_MINUTES = 30L

        fun isInScheduledWindow(now: LocalTime, scheduled: LocalTime): Boolean {
            val start = scheduled.minusMinutes(TIME_WINDOW_MINUTES)
            val end = scheduled.plusMinutes(TIME_WINDOW_MINUTES)
            return !now.isBefore(start) && !now.isAfter(end)
        }

        fun shouldCheckAt(now: LocalTime, scheduled: ScheduledNotificationTime): Boolean =
            isInScheduledWindow(now, scheduled.toLocalTime())
    }
}
