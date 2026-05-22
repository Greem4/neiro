package ru.greemlab.neiro.sync

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.network.YClientsRepository
import java.util.concurrent.TimeUnit

/**
 * Автоматическая синхронизация YClients:
 * - при возврате приложения на передний план (если данные устарели);
 * - периодически в фоне через [YClientsSyncWorker].
 */
object AutoSyncCoordinator {

    private const val PERIODIC_WORK_NAME = "yclients_periodic_sync"
    private const val FOREGROUND_STALE_MS = 30 * 60 * 1000L
    private const val PERIODIC_INTERVAL_HOURS = 4L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        val appContext = context.applicationContext
        val yclientsRepository = YClientsRepository.getInstance(appContext)
        val syncPreferences = SyncPreferences.get(appContext)

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    scope.launch {
                        syncIfStale(appContext, syncPreferences, yclientsRepository, force = false)
                    }
                }
            },
        )

        scope.launch {
            yclientsRepository.isLoggedIn
                .collect { loggedIn ->
                    if (loggedIn && syncPreferences.isAutoSyncEnabled) {
                        schedulePeriodicSync(appContext)
                    } else {
                        cancelPeriodicSync(appContext)
                    }
                }
        }
    }

    /**
     * Синхронизирует, если включена автосинхронизация, пользователь вошёл в YClients
     * и с прошлой успешной синхронизации прошло больше [FOREGROUND_STALE_MS].
     */
    suspend fun syncIfStale(
        context: Context,
        syncPreferences: SyncPreferences = SyncPreferences.get(context),
        yclientsRepository: YClientsRepository = YClientsRepository.getInstance(context),
        force: Boolean = false,
    ): SyncOutcome? {
        if (!syncPreferences.isAutoSyncEnabled) return null
        if (!yclientsRepository.isLoggedIn.first()) return null

        val lastSync = syncPreferences.lastSyncEpochMillis()
        if (!force && lastSync > 0L && System.currentTimeMillis() - lastSync < FOREGROUND_STALE_MS) {
            return null
        }

        return YClientsCalendarSync.get(context).syncDefaultAutoRange()
    }

    fun schedulePeriodicSync(context: Context) {
        if (!SyncPreferences.get(context).isAutoSyncEnabled) return
        if (!YClientsRepository.getInstance(context).isLoggedIn.value) return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<YClientsSyncWorker>(
            PERIODIC_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelPeriodicSync(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    fun onAutoSyncToggled(context: Context, enabled: Boolean) {
        if (enabled) {
            schedulePeriodicSync(context)
        } else {
            cancelPeriodicSync(context)
        }
    }
}
