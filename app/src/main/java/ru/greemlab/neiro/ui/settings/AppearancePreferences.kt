package ru.greemlab.neiro.ui.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Оформление приложения поверх выбранной темы (светлая/тёмная/системная).
 *
 * Живёт в отдельных prefs, а не в DataStore рядом с темой: тот держит данные
 * календаря, и класть туда тумблер оформления значит будить перерисовку всего
 * снимка состояния ради одного `Boolean`.
 *
 * Стеклянный вид включён по умолчанию: приложение с ним и задумано, и первый
 * запуск должен показать его сразу. Дефолт срабатывает только пока ключа в
 * prefs нет — стоит переключить тумблер, и дальше решает выбор пользователя:
 * выключил стекло — оно остаётся выключенным.
 */
class AppearancePreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Общий StateFlow: [get] — синглтон, поэтому переключение на экране настроек
    // сразу видно и теме приложения, и открытым диалогам.
    private val _glassEnabled = MutableStateFlow(prefs.getBoolean(KEY_GLASS, DEFAULT_GLASS))
    val glassEnabledFlow: StateFlow<Boolean> = _glassEnabled.asStateFlow()

    var isGlassEnabled: Boolean
        get() = prefs.getBoolean(KEY_GLASS, DEFAULT_GLASS)
        set(value) {
            prefs.edit().putBoolean(KEY_GLASS, value).apply()
            _glassEnabled.value = value
        }

    companion object {
        private const val PREFS_NAME = "neiro_appearance_prefs"
        private const val KEY_GLASS = "glass_surfaces"
        private const val DEFAULT_GLASS = true

        @Volatile
        private var instance: AppearancePreferences? = null

        fun get(context: Context): AppearancePreferences =
            instance ?: synchronized(this) {
                instance ?: AppearancePreferences(context).also { instance = it }
            }
    }
}
