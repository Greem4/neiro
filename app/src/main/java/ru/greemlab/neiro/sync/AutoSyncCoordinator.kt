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
 * Разовая подтяжка при входе/открытии приложения — [LiveApiCoordinator]. Периодический
 * WorkManager каждые 4 ч отключён; фоновые изменения приходят push'ом с сервера.
 */
object AutoSyncCoordinator {

    /** Имя старой периодической задачи — отменяем при старте для обновления с установленной версии. */
    private const val LEGACY_PERIODIC_WORK_NAME = "yclients_periodic_sync"

    /**
     * Осиротевшие уникальные работы удалённых воркеров (Этап 8: локальный опрос
     * YClients и FCM-синк по действию "sync" убраны, показ теперь идёт прямо из
     * payload). Классов, из которых можно было бы взять эти имена, больше нет —
     * держим строками здесь.
     */
    private const val ORPHANED_LIVE_API_REFRESH_WORK_NAME = "yclients_live_api_refresh"
    private const val ORPHANED_PUSH_FCM_SYNC_WORK_NAME = "push_fcm_sync"

    private const val DAILY_STALE_MS = 24 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false

    /**
     * Вызывается с IO-потока: создание [YClientsRepository] тянет за собой
     * `TokenStorage` (AndroidKeyStore + дисковый I/O), которому на main делать
     * нечего. На main остаётся только подписка на [ProcessLifecycleOwner] —
     * она main-only по контракту.
     */
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true

        val appContext = context.applicationContext
        val yclientsRepository = YClientsRepository.getInstance(appContext)
        val syncPreferences = SyncPreferences.get(appContext)

        cancelLegacyPeriodicSync(appContext)
        WorkManager.getInstance(appContext).apply {
            cancelUniqueWork(ORPHANED_LIVE_API_REFRESH_WORK_NAME)
            cancelUniqueWork(ORPHANED_PUSH_FCM_SYNC_WORK_NAME)
        }

        if (!syncPreferences.hasCompletedInitialFullSync &&
            CalendarDataStoreProvider.peekDayData(appContext).isNotEmpty()
        ) {
            syncPreferences.markInitialFullSyncComplete()
        }

        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope.launch {
                    syncDailyIfStale(appContext, syncPreferences, yclientsRepository, force = false)
                }
            }
        }
        // addObserver обязан идти с main. Приложение к этому моменту может уже быть
        // STARTED — LifecycleRegistry догоняет новичка событиями до текущего
        // состояния, поэтому первый onStart не теряется.
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        }
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

        val outcome = YClientsCalendarSync.get(context).syncDefaultAutoRange()
        // Одноразовое заполнение истории денег: у тех, кто вошёл в YClients
        // давно, экрана логина больше не будет, и другого случая подтянуть
        // 2025 год не представится. Внутри стоит свой флаг — повторов нет.
        // Деньги не должны ломать календарь: ошибка остаётся здесь.
        runCatching { SalaryHistorySync.get(context).ensureHistoryPulledOnce() }
        return outcome
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
