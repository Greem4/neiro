package ru.greemlab.neiro.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.time.LocalTime

/**
 * Единый периодический «notification tick»: сводки, напоминание об архиве
 * и fallback-проверка окна напоминаний о занятиях.
 */
class SessionDailyNotificationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        SessionNotificationCoordinator.runDailyScheduledChecks(applicationContext)
        SessionNotificationCoordinator.runReminderCheck(applicationContext)
        return Result.success()
    }

    companion object {
        /**
         * Пора показать сегодня: наступило настроенное время (до полуночи).
         * Не узкое «окно» — иначе периодический воркер (раз в 15 мин) часто промахивается.
         */
        fun isDueToday(now: LocalTime, scheduled: ScheduledNotificationTime): Boolean =
            !now.isBefore(scheduled.toLocalTime())
    }
}
