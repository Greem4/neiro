package ru.greemlab.neiro.data.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.greemlab.neiro.BuildConfig

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

    private val _userAvatarUrl = MutableStateFlow(readUserAvatarUrl())
    val userAvatarUrlFlow: StateFlow<String?> = _userAvatarUrl.asStateFlow()

    /**
     * Partner Token: по умолчанию берётся из BuildConfig (зашит в приложение через local.properties).
     * Можно переопределить через UI настроек (на случай ротации без пересборки).
     */
    var partnerToken: String
        get() = prefs.getString(KEY_PARTNER_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.YCLIENTS_PARTNER_TOKEN
        set(value) = prefs.edit().putString(KEY_PARTNER_TOKEN, value).apply()

    var userToken: String?
        get() = prefs.getString(KEY_USER_TOKEN, null)
        set(value) {
            prefs.edit().putString(KEY_USER_TOKEN, value).apply()
            _isLoggedIn.value = value != null
        }

    var companyId: Int
        get() = prefs.getInt(KEY_COMPANY_ID, BuildConfig.YCLIENTS_COMPANY_ID)
        set(value) {
            val oldId = prefs.getInt(KEY_COMPANY_ID, -1)
            if (oldId != value) {
                prefs.edit()
                    .putInt(KEY_COMPANY_ID, value)
                    .remove(KEY_STAFF_ID)
                    .apply()
            }
        }

    var staffId: Int?
        get() {
            val savedForCompany = prefs.getInt(KEY_STAFF_ID_COMPANY, -1)
            if (savedForCompany != companyId) return null
            return prefs.getInt(KEY_STAFF_ID, -1).takeIf { it >= 0 }
        }
        set(value) {
            prefs.edit()
                .putInt(KEY_STAFF_ID, value ?: -1)
                .putInt(KEY_STAFF_ID_COMPANY, if (value != null) companyId else -1)
                .apply()
        }

    var userLogin: String?
        get() = prefs.getString(KEY_USER_LOGIN, null)
        set(value) = prefs.edit().putString(KEY_USER_LOGIN, value).apply()

    var userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    var userAvatarUrl: String?
        get() = readUserAvatarUrl()
        set(value) {
            val normalized = value?.trim()?.takeIf { it.isNotBlank() }
            prefs.edit().apply {
                if (normalized == null) remove(KEY_USER_AVATAR) else putString(KEY_USER_AVATAR, normalized)
            }.apply()
            _userAvatarUrl.value = normalized
        }

    fun hasUserToken(): Boolean = userToken != null

    fun clear() {
        prefs.edit()
            .remove(KEY_USER_TOKEN)
            .remove(KEY_USER_LOGIN)
            .remove(KEY_USER_NAME)
            .remove(KEY_USER_AVATAR)
            .remove(KEY_STAFF_ID)
            .remove(KEY_STAFF_ID_COMPANY)
            .apply()
        _isLoggedIn.value = false
        _userAvatarUrl.value = null
    }

    private fun readUserAvatarUrl(): String? =
        prefs.getString(KEY_USER_AVATAR, null)?.trim()?.takeIf { it.isNotBlank() }

    companion object {
        private const val PREFS_NAME = "yclients_auth"
        private const val KEY_PARTNER_TOKEN = "partner_token"
        private const val KEY_USER_TOKEN = "user_token"
        private const val KEY_COMPANY_ID = "company_id"
        private const val KEY_STAFF_ID = "staff_id"
        private const val KEY_STAFF_ID_COMPANY = "staff_id_company"
        private const val KEY_USER_LOGIN = "user_login"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_AVATAR = "user_avatar"
    }
}
