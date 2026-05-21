package ru.greemlab.neiro.data.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Безопасное хранилище токенов авторизации.
 * Использует EncryptedSharedPreferences для защиты данных.
 */
class TokenStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _isLoggedIn = MutableStateFlow(hasUserToken())
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    var partnerToken: String
        get() = prefs.getString(KEY_PARTNER_TOKEN, DEFAULT_PARTNER_TOKEN) ?: DEFAULT_PARTNER_TOKEN
        set(value) = prefs.edit().putString(KEY_PARTNER_TOKEN, value).apply()

    var userToken: String?
        get() = prefs.getString(KEY_USER_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_USER_TOKEN, value).apply()
            _isLoggedIn.value = value != null
        }

    var companyId: Int
        get() = prefs.getInt(KEY_COMPANY_ID, DEFAULT_COMPANY_ID)
        set(value) = prefs.edit().putInt(KEY_COMPANY_ID, value).apply()

    var staffId: Int?
        get() = prefs.getInt(KEY_STAFF_ID, -1).takeIf { it >= 0 }
        set(value) = prefs.edit().putInt(KEY_STAFF_ID, value ?: -1).apply()

    var userLogin: String?
        get() = prefs.getString(KEY_USER_LOGIN, null)
        set(value) = prefs.edit().putString(KEY_USER_LOGIN, value).apply()

    var userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    fun hasUserToken(): Boolean = userToken != null

    fun clear() {
        prefs.edit()
            .remove(KEY_USER_TOKEN)
            .remove(KEY_USER_LOGIN)
            .remove(KEY_USER_NAME)
            .remove(KEY_STAFF_ID)
            .apply()
        _isLoggedIn.value = false
    }

    companion object {
        private const val PREFS_NAME = "yclients_auth"
        private const val KEY_PARTNER_TOKEN = "partner_token"
        private const val KEY_USER_TOKEN = "user_token"
        private const val KEY_COMPANY_ID = "company_id"
        private const val KEY_STAFF_ID = "staff_id"
        private const val KEY_USER_LOGIN = "user_login"
        private const val KEY_USER_NAME = "user_name"

        // ID компании из URL пользователя
        private const val DEFAULT_COMPANY_ID = 520135

        // Partner token нужно получить на developer.yclients.com
        // Пока используем placeholder — пользователь должен ввести свой
        private const val DEFAULT_PARTNER_TOKEN = ""
    }
}
