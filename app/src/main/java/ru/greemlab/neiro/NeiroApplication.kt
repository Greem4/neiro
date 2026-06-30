package ru.greemlab.neiro

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.CalendarDataStoreProvider
import ru.greemlab.neiro.notifications.SessionNotificationCoordinator
import ru.greemlab.neiro.push.PushRegistrar
import ru.greemlab.neiro.sync.AutoSyncCoordinator
import ru.greemlab.neiro.sync.LiveApiCoordinator

class NeiroApplication : Application() {

    /** Application-scope для фоновых задач прогрева и миграций. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Синхронный SharedPreferences-кэш заполняет снимок прямо в конструкторе репозитория,
        // поэтому UI стартует с данными без блокировки main-потока.
        val repository = CalendarDataStoreProvider.get(this)

        // ProcessLifecycleOwner.addObserver обязан быть на main thread — оставляем здесь.
        AutoSyncCoordinator.initialize(this)
        LiveApiCoordinator.initialize(this)

        // Фоновая гидратация из DataStore + миграции — параллельно со стартом UI.
        appScope.launch {
            // Эти init делают disk I/O (WorkManager.enqueue, FCM token) — не блокируем main.
            SessionNotificationCoordinator.initialize(this@NeiroApplication)
            PushRegistrar.initialize(this@NeiroApplication)

            repository.warmUp()
            repository.migrateProfileIfNeeded()
            SessionNotificationCoordinator.refreshFromCalendar(this@NeiroApplication)
        }
    }
}
