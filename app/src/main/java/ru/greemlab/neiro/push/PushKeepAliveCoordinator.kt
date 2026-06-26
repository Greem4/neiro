package ru.greemlab.neiro.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import ru.greemlab.neiro.sync.LiveApiPollSchedule
import java.util.concurrent.TimeUnit

object PushKeepAliveCoordinator {

    private const val WORK_NAME = "push_keepalive"

    private const val DAY_INTERVAL_MINUTES = 30L
    private const val NIGHT_INTERVAL_MINUTES = 60L

    fun schedule(context: Context, delayMs: Long = 0L) {
        if (!PushConfig.isActive) return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<PushKeepAliveWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun scheduleNext(context: Context) {
        schedule(context, delayMs = intervalMillis())
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(WORK_NAME)
    }

    private fun intervalMillis(): Long = if (LiveApiPollSchedule.isQuietHours()) {
        TimeUnit.MINUTES.toMillis(NIGHT_INTERVAL_MINUTES)
    } else {
        TimeUnit.MINUTES.toMillis(DAY_INTERVAL_MINUTES)
    }
}
