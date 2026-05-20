package ru.greemlab.neiro.data

import android.content.Context
import ru.greemlab.neiro.domain.models.UserProfile
import java.time.LocalDate

/**
 * Единый экземпляр хранилища на приложение.
 * DCL гарантирует дешёвое получение из любого потока после первой инициализации.
 */
object CalendarDataStoreProvider {

    @Volatile
    private var instance: CalendarRepository? = null

    fun get(context: Context): CalendarRepository =
        instance ?: synchronized(this) {
            instance ?: CalendarDataStore(context.applicationContext).also { instance = it }
        }

    fun peekSnapshot(context: Context): StoreSnapshot = get(context).peekSnapshot()

    fun peekProfile(context: Context): UserProfile = peekSnapshot(context).profile

    fun peekDayData(context: Context): Map<LocalDate, List<String>> = peekSnapshot(context).dayData

    fun peekTheme(context: Context): String = peekSnapshot(context).theme
}
