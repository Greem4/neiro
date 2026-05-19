package ru.greemlab.neiro

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.CalendarDataStoreProvider

class NeiroApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Прогреваем DataStore до открытия Activity — UI стартует уже с данными.
        CalendarDataStoreProvider.warmUp(this)
        // Миграция профиля делается отдельно, не на пути запуска.
        appScope.launch {
            CalendarDataStoreProvider.get(this@NeiroApplication).migrateProfileIfNeeded()
        }
    }
}
