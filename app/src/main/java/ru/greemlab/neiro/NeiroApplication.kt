package ru.greemlab.neiro

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.CalendarDataStoreProvider
import ru.greemlab.neiro.sync.AutoSyncCoordinator

class NeiroApplication : Application() {

    /** Application-scope для фоновых задач прогрева и миграций. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        AutoSyncCoordinator.initialize(this)

        // Синхронный SharedPreferences-кэш заполняет снимок прямо в конструкторе репозитория,
        // поэтому UI стартует с данными без блокировки main-потока.
        val repository = CalendarDataStoreProvider.get(this)

        // Фоновая гидратация из DataStore + миграции — параллельно со стартом UI.
        appScope.launch {
            repository.warmUp()
            repository.migrateProfileIfNeeded()
        }
    }
}
