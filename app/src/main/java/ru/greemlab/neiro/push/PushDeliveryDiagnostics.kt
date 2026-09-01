package ru.greemlab.neiro.push

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Состояние канала мгновенных уведомлений — то, что видно человеку.
 *
 * Пуш приходит только на устройство с живым токеном FCM. Токен Firebase выдаёт
 * не всегда: без Google-сервисов, при их старой версии или заблокированном
 * доступе к серверам Google `getToken()` просто не отвечает. Раньше это было
 * молчаливым исходом — токен не приезжал, приложение жило на получасовом
 * догоне, и снаружи это выглядело как «уведомления приходят, но с задержкой»
 * (IN2010, 01.09.2026: обе установки неделю прожили с пустым токеном).
 *
 * Здесь состояние хранится, чтобы его можно было показать на экране настроек
 * уведомлений: работает канал или нет, и почему.
 */
object PushDeliveryDiagnostics {

    private const val PREFS_NAME = "push_diagnostics"
    private const val KEY_LAST_TOKEN_AT = "last_token_at"
    private const val KEY_LAST_DELIVERED_AT = "last_delivered_at"
    private const val KEY_LAST_ERROR = "last_error"

    /** Что известно про канал пушей прямо сейчас. */
    data class State(
        /** Firebase хоть раз отдал токен на этом устройстве. */
        val hasToken: Boolean = false,
        /** Токен доехал до push-сервера — с этого момента пуши могут приходить. */
        val deliveredToServer: Boolean = false,
        /** Почему Firebase не отдал токен; пусто — ошибки не было. */
        val lastError: String = "",
        /** Сборка вообще умеет пуши: есть google-services.json и адрес сервера. */
        val isSupported: Boolean = PushConfig.isActive,
    ) {
        /** Мгновенные уведомления работают: токен есть и сервер о нём знает. */
        val isWorking: Boolean get() = isSupported && hasToken && deliveredToServer
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Читает сохранённое состояние — вызывать при старте приложения. */
    fun warmUp(context: Context) {
        val prefs = prefs(context)
        _state.value = State(
            hasToken = prefs.getLong(KEY_LAST_TOKEN_AT, 0L) > 0L,
            deliveredToServer = prefs.getLong(KEY_LAST_DELIVERED_AT, 0L) > 0L,
            lastError = prefs.getString(KEY_LAST_ERROR, "").orEmpty(),
        )
    }

    /** Firebase отдал токен. */
    fun onTokenReceived(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_TOKEN_AT, System.currentTimeMillis())
            .remove(KEY_LAST_ERROR)
            .apply()
        _state.value = _state.value.copy(hasToken = true, lastError = "")
    }

    /**
     * Firebase токен не отдал. [reason] — текст исключения: без него «нет
     * Google-сервисов», «нет сети» и «Firebase не настроен» выглядят одинаково.
     */
    fun onTokenFailed(context: Context, reason: String) {
        val trimmed = reason.take(MAX_REASON_LENGTH)
        prefs(context).edit().putString(KEY_LAST_ERROR, trimmed).apply()
        _state.value = _state.value.copy(lastError = trimmed)
    }

    /** Токен принят push-сервером. */
    fun onTokenDelivered(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_DELIVERED_AT, System.currentTimeMillis())
            .apply()
        _state.value = _state.value.copy(deliveredToServer = true)
    }

    /** Выход из аккаунта: устройство на сервере отозвано, знание о нём устарело. */
    fun onLogout(context: Context) {
        prefs(context).edit().remove(KEY_LAST_DELIVERED_AT).apply()
        _state.value = _state.value.copy(deliveredToServer = false)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val MAX_REASON_LENGTH = 200
}
