package ru.greemlab.neiro

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.CalendarDataStoreProvider

class NeiroApplication : Application() {

    // Single app-scope для фоновых задач прогрева и миграций.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Синхронный SharedPreferences-кэш заполняет StoreSnapshot прямо в конструкторе
        // CalendarDataStore — поэтому UI стартует с данными без блокировки main-потока.
        CalendarDataStoreProvider.get(this)

        // Фоновая гидратация из DataStore + миграции; запускается параллельно со стартом UI.
        appScope.launch {
            val store = CalendarDataStoreProvider.get(this@NeiroApplication)
            store.warmUp()
            store.migrateProfileIfNeeded()
        }
    }
}
