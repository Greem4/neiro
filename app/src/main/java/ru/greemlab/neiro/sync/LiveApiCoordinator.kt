package ru.greemlab.neiro.sync

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.network.YClientsRepository
import ru.greemlab.neiro.push.PushConfig
import ru.greemlab.neiro.push.PushKeepAliveCoordinator
import ru.greemlab.neiro.push.PushRegistrar
import java.util.concurrent.TimeUnit

/**
 * Независимый от [AutoSyncCoordinator] опрос YClients API:
 * - сразу после входа и при возврате в приложение;
 * - на переднем плане: [LiveApiPollSchedule.DAY_INTERVAL_MINUTES] днём,
 *   [LiveApiPollSchedule.NIGHT_INTERVAL_MINUTES] с 21:00 до 09:00;
 * - между полными подтяжками — только записи с `changed_after` (лёгкий трафик);
 * - в фоне — те же интервалы через цепочку [LiveApiRefreshWorker].
 */
object LiveApiCoordinator {

    const val BACKGROUND_WORK_NAME = "yclients_live_api_refresh"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false
    private var foregroundPollJob: Job? = null

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        val appContext = context.applicationContext
        val yclientsRepository = YClientsRepository.getInstance(appContext)
        val serverPushActive = PushConfig.isActive

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    scope.launch {
                        if (!yclientsRepository.isLoggedIn.first()) return@launch
                        if (serverPushActive) {
                            PushRegistrar.onAppForeground(appContext)
                        }
                        refreshNow(appContext)
                        // Foreground-поллинг остаётся и при активном push: FCM может
                        // задержаться/потеряться, а keepalive — фоновый backup раз
                        // в 30–60 мин, этого мало для открытого экрана календаря.
                        startForegroundPolling(appContext)
                    }
                }

                override fun onStop(owner: LifecycleOwner) {
                    stopForegroundPolling()
                }
            },
        )

        scope.launch {
            yclientsRepository.isLoggedIn.collect { loggedIn ->
                if (loggedIn) {
                    if (serverPushActive) {
                        PushKeepAliveCoordinator.schedule(appContext)
                    } else {
                        scheduleBackgroundRefresh(appContext, delayMs = 0L)
                    }
                    refreshNow(appContext)
                } else {
                    stopForegroundPolling()
                    cancelBackgroundRefresh(appContext)
                    if (serverPushActive) {
                        PushKeepAliveCoordinator.cancel(appContext)
                    }
                }
            }
        }
    }

    private fun startForegroundPolling(context: Context) {
        stopForegroundPolling()
        foregroundPollJob = scope.launch {
            while (isActive) {
                delay(LiveApiPollSchedule.intervalMillis())
                refreshNow(context)
            }
        }
    }

    private fun stopForegroundPolling() {
        foregroundPollJob?.cancel()
        foregroundPollJob = null
    }

    private suspend fun refreshNow(context: Context) {
        val yclientsRepository = YClientsRepository.getInstance(context)
        if (!yclientsRepository.isLoggedIn.first()) return
        YClientsCalendarSync.get(context).refreshLiveRange()
    }

    /** Следующий фоновый запуск; вызывается из [LiveApiRefreshWorker] после каждого опроса. */
    fun scheduleNextBackgroundRefresh(context: Context) {
        if (PushConfig.isActive) return
        // Self-reschedule происходит из finally работающего worker'а: он ещё RUNNING,
        // и KEEP молча отбросил бы новый запрос, обрывая цепочку опросов.
        scheduleBackgroundRefresh(
            context,
            delayMs = LiveApiPollSchedule.intervalMillis(),
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
        )
    }

    private fun scheduleBackgroundRefresh(
        context: Context,
        delayMs: Long,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    ) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<LiveApiRefreshWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            BACKGROUND_WORK_NAME,
            policy,
            request,
        )
    }

    private fun cancelBackgroundRefresh(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(BACKGROUND_WORK_NAME)
    }
}
