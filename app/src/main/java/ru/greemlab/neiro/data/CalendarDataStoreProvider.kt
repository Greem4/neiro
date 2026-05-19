package ru.greemlab.neiro.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.greemlab.neiro.domain.models.UserProfile
import java.time.LocalDate

/**
 * Единый экземпляр хранилища на приложение — все ViewModel читают одни и те же данные.
 */
object CalendarDataStoreProvider {
    @Volatile
    private var instance: CalendarDataStore? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun get(context: Context): CalendarDataStore {
        return instance ?: synchronized(this) {
            instance ?: CalendarDataStore(context.applicationContext).also { instance = it }
        }
    }

    /** Запускается из [ru.greemlab.neiro.NeiroApplication] до Activity. */
    fun warmUp(context: Context) {
        scope.launch {
            get(context).warmUp()
        }
    }

    fun peekProfile(context: Context): UserProfile = get(context).peekSnapshot().profile

    fun peekDayData(context: Context): Map<LocalDate, List<String>> =
        get(context).peekSnapshot().dayData
}
