package ru.greemlab.neiro.theme

import ru.greemlab.neiro.data.THEME_DARK
import ru.greemlab.neiro.data.THEME_LIGHT
import ru.greemlab.neiro.data.THEME_SYSTEM

/**
 * Что выбрано в настройках: светлая, тёмная или «как в системе».
 *
 * Хранится строкой — значение [storageId] лежит в DataStore приложения
 * (`CalendarDataStore`, ключ `app_theme`), поэтому переименовывать его нельзя:
 * записанное старой версией должна прочитать новая. Внутри кода со строками
 * работать не надо, есть [fromStorageId].
 *
 * Устройство тема не покидает: в файл экспорта архива она не входит, в
 * синхронизацию с YClients тоже, а облачный бэкап Android выключен целиком
 * (`backup_rules.xml`).
 *
 * Это «какой яркости» тема, а не «какого цвета» — цвет живёт отдельно, в
 * [NeiroPalette], и одна палитра всегда умеет обе яркости.
 */
enum class ThemeMode(val storageId: String) {
    SYSTEM(THEME_SYSTEM),
    LIGHT(THEME_LIGHT),
    DARK(THEME_DARK),
    ;

    /**
     * Нужна ли тёмная тема при текущем состоянии системы.
     *
     * @param systemDark что сейчас у системы (`isSystemInDarkTheme()`).
     */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** Неизвестное или пустое значение из хранилища читается как [SYSTEM]. */
        fun fromStorageId(id: String?): ThemeMode =
            entries.firstOrNull { it.storageId == id } ?: SYSTEM
    }
}
