package ru.greemlab.neiro.sync

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.CalendarDataStoreProvider
import ru.greemlab.neiro.data.network.YClientsRepository

/**
 * Ежедневная автосинхронизация YClients при возврате в приложение (текущий + следующий месяц).
 *
 * Live-опрос текущего месяца — [LiveApiCoordinator]. Периодический WorkManager каждые 4 ч отключён.
 */
object AutoSyncCoordinator {

    /** Имя старой периодической задачи — отменяем при старте для обновления с установленной версии. */
    private const val LEGACY_PERIODIC_WORK_NAME = "yclients_periodic_sync"
    private const val DAILY_STALE_MS = 24 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        val appContext = context.applicationContext
        val yclientsRepository = YClientsRepository.getInstance(appContext)
        val syncPreferences = SyncPreferences.get(appContext)

        cancelLegacyPeriodicSync(appContext)

        if (!syncPreferences.hasCompletedInitialFullSync &&
            CalendarDataStoreProvider.peekDayData(appContext).isNotEmpty()
        ) {
            syncPreferences.markInitialFullSyncComplete()
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    scope.launch {
                        syncDailyIfStale(appContext, syncPreferences, yclientsRepository, force = false)
                    }
                }
            },
        )
    }

    /**
     * Синхронизирует текущий и следующий месяц, если включена автосинхронизация,
     * пользователь вошёл в YClients и с прошлой успешной дневной синхронизации прошло > 24 ч.
     */
    suspend fun syncDailyIfStale(
        context: Context,
        syncPreferences: SyncPreferences = SyncPreferences.get(context),
        yclientsRepository: YClientsRepository = YClientsRepository.getInstance(context),
        force: Boolean = false,
    ): SyncOutcome? {
        if (!syncPreferences.isAutoSyncEnabled) return null
        if (!yclientsRepository.isLoggedIn.first()) return null

        val lastSync = syncPreferences.lastSyncEpochMillis()
        if (!force && lastSync > 0L && System.currentTimeMillis() - lastSync < DAILY_STALE_MS) {
            return null
        }

        return YClientsCalendarSync.get(context).syncDefaultAutoRange()
    }

    fun cancelLegacyPeriodicSync(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(LEGACY_PERIODIC_WORK_NAME)
    }

    fun onAutoSyncToggled(context: Context, enabled: Boolean) {
        cancelLegacyPeriodicSync(context)
        if (enabled) {
            scope.launch {
                syncDailyIfStale(
                    context,
                    force = true,
                )
            }
        }
    }
}
