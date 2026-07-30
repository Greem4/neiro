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

        // Всё, что трогает TokenStorage (KeyStore + EncryptedSharedPreferences),
        // WorkManager и FCM — на IO. Прежний «прогрев» отдельной корутиной гонку
        // не выигрывал: initialize шли на main следующей же строкой и всё равно
        // упирались в создание хранилища (D1). Подписку на ProcessLifecycleOwner
        // координаторы сами возвращают на main.
        appScope.launch {
            AutoSyncCoordinator.initialize(this@NeiroApplication)
            LiveApiCoordinator.initialize(this@NeiroApplication)

            SessionNotificationCoordinator.initialize(this@NeiroApplication)
            PushRegistrar.initialize(this@NeiroApplication)

            repository.warmUp()
            repository.migrateProfileIfNeeded()
            SessionNotificationCoordinator.refreshFromCalendar(this@NeiroApplication)
        }
    }
}
