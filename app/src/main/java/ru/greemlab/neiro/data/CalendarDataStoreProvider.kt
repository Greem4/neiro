package ru.greemlab.neiro.data

import android.content.Context

/**
 * Единый экземпляр хранилища на приложение — все ViewModel читают одни и те же данные.
 */
object CalendarDataStoreProvider {
    @Volatile
    private var instance: CalendarDataStore? = null

    fun get(context: Context): CalendarDataStore {
        return instance ?: synchronized(this) {
            instance ?: CalendarDataStore(context.applicationContext).also { instance = it }
        }
    }
}
