package ru.greemlab.neiro.data.network

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.greemlab.neiro.BuildConfig

/**
 * Безопасное хранение сессии YClients и пользовательских настроек API.
 *
 * Данные переживают перезапуск приложения и обновление APK поверх существующей установки.
 */
class TokenStorage(context: Context) {

    private val prefs: SharedPreferences = createSecurePrefs(context.applicationContext)

    private val _isLoggedIn = MutableStateFlow(!prefs.getString(KEY_USER_TOKEN, null).isNullOrBlank())
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _userAvatarUrlFlow = MutableStateFlow(prefs.getString(KEY_USER_AVATAR_URL, null))
    val userAvatarUrlFlow: StateFlow<String?> = _userAvatarUrlFlow.asStateFlow()

    var userToken: String?
        get() = prefs.getString(KEY_USER_TOKEN, null)
        set(value) {
            prefs.edit { putString(KEY_USER_TOKEN, value?.takeIf { it.isNotBlank() }) }
            _isLoggedIn.value = !value.isNullOrBlank()
        }

    var userLogin: String?
        get() = prefs.getString(KEY_USER_LOGIN, null)
        set(value) {
            prefs.edit { putString(KEY_USER_LOGIN, value?.trim()?.takeIf { it.isNotBlank() }) }
        }

    var userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) {
            prefs.edit { putString(KEY_USER_NAME, value?.trim()?.takeIf { it.isNotBlank() }) }
        }

    var userAvatarUrl: String?
        get() = prefs.getString(KEY_USER_AVATAR_URL, null)
        set(value) {
            val normalized = value?.trim()?.takeIf { it.isNotBlank() }
            prefs.edit { putString(KEY_USER_AVATAR_URL, normalized) }
            _userAvatarUrlFlow.value = normalized
        }

    var partnerToken: String
        get() = prefs.getString(KEY_PARTNER_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.YCLIENTS_PARTNER_TOKEN
        set(value) {
            prefs.edit { putString(KEY_PARTNER_TOKEN, value.trim()) }
        }

    var companyId: Int
        get() {
            val fromPrefs = prefs.getInt(KEY_COMPANY_ID, -1)
            if (fromPrefs > 0) return fromPrefs
            val fromBuildConfig = BuildConfig.YCLIENTS_COMPANY_ID
            return if (fromBuildConfig > 0) fromBuildConfig else DEFAULT_COMPANY_ID
        }
        set(value) {
            if (value > 0) {
                val oldId = prefs.getInt(KEY_COMPANY_ID, -1)
                if (oldId != value) {
                    prefs.edit {
                        putInt(KEY_COMPANY_ID, value)
                        remove(KEY_STAFF_ID)
                        remove(KEY_STAFF_ID_COMPANY)
                    }
                }
            }
        }

    var staffId: Int?
        get() {
            val savedForCompany = prefs.getInt(KEY_STAFF_ID_COMPANY, -1)
            if (savedForCompany != companyId) return null
            return prefs.getInt(KEY_STAFF_ID, -1).takeIf { it > 0 }
        }
        set(value) {
            prefs.edit {
                if (value != null && value > 0) {
                    putInt(KEY_STAFF_ID, value)
                    putInt(KEY_STAFF_ID_COMPANY, companyId)
                } else {
                    remove(KEY_STAFF_ID)
                    remove(KEY_STAFF_ID_COMPANY)
                }
            }
        }

    fun clear() {
        prefs.edit {
            remove(KEY_USER_TOKEN)
            remove(KEY_USER_LOGIN)
            remove(KEY_USER_NAME)
            remove(KEY_USER_AVATAR_URL)
            remove(KEY_STAFF_ID)
            remove(KEY_STAFF_ID_COMPANY)
        }
        _isLoggedIn.value = false
        _userAvatarUrlFlow.value = null
    }

    private fun createSecurePrefs(appContext: Context): SharedPreferences {
        val fallback = appContext.getSharedPreferences(PREFS_FALLBACK_NAME, Context.MODE_PRIVATE)
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_SECURE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (t: Throwable) {
            android.util.Log.e(
                "TokenStorage",
                "EncryptedSharedPreferences failed, falling back to plain prefs. " +
                    "Токены YClients будут храниться нешифрованно на этом устройстве.",
                t,
            )
            fallback
        }
    }

    companion object {
        private const val PREFS_SECURE_NAME = "neiro_yclients_secure"
        private const val PREFS_FALLBACK_NAME = "neiro_yclients_fallback"

        private const val KEY_USER_TOKEN = "user_token"
        private const val KEY_USER_LOGIN = "user_login"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_AVATAR_URL = "user_avatar_url"
        private const val KEY_PARTNER_TOKEN = "partner_token"
        private const val KEY_COMPANY_ID = "company_id"
        private const val KEY_STAFF_ID = "staff_id"
        private const val KEY_STAFF_ID_COMPANY = "staff_id_company"

        private const val DEFAULT_COMPANY_ID = 520135
    }
}
