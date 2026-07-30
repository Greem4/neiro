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

    /** Регистрация не удалась — до следующей попытки не ждём полный интервал без push. */
    private const val RETRY_INTERVAL_MINUTES = 5L

    fun schedule(
        context: Context,
        delayMs: Long = 0L,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    ) {
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
            policy,
            request,
        )
    }

    /**
     * Self-reschedule из работающего worker'а: KEEP отбросил бы запрос (worker ещё RUNNING).
     * Это единственный механизм продолжения цепочки — воркер не ретраит, иначе
     * отретраенная работа и новое звено копились бы в очереди одновременно.
     */
    fun scheduleNext(context: Context, afterFailure: Boolean = false) {
        schedule(
            context,
            delayMs = intervalMillis(afterFailure),
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(WORK_NAME)
    }

    private fun intervalMillis(afterFailure: Boolean): Long = when {
        afterFailure -> TimeUnit.MINUTES.toMillis(RETRY_INTERVAL_MINUTES)
        LiveApiPollSchedule.isQuietHours() -> TimeUnit.MINUTES.toMillis(NIGHT_INTERVAL_MINUTES)
        else -> TimeUnit.MINUTES.toMillis(DAY_INTERVAL_MINUTES)
    }
}
