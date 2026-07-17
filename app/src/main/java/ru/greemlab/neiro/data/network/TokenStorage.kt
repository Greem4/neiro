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
            return BuildConfig.YCLIENTS_COMPANY_ID.takeIf { it > 0 } ?: 0
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

    /**
     * Порядок восстановления при ошибке создания шифрованного хранилища:
     *  1. удалить повреждённый keyset (лежит внутри prefs-файла) и master key, повторить;
     *  2. при повторном провале — хранить сессию только в памяти процесса.
     * Plaintext-fallback на диск не используется: токены YClients не должны
     * лежать в открытом виде.
     */
    private fun createSecurePrefs(appContext: Context): SharedPreferences {
        createEncryptedPrefs(appContext)?.let { prefs ->
            // Успех: подчищаем возможный plaintext-fallback из старых версий.
            appContext.deleteSharedPreferences(PREFS_LEGACY_FALLBACK_NAME)
            return prefs
        }

        android.util.Log.w(TAG, "Пересоздаём хранилище токенов после ошибки keyset")
        appContext.deleteSharedPreferences(PREFS_SECURE_NAME)
        runCatching {
            java.security.KeyStore.getInstance("AndroidKeyStore")
                .apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
        createEncryptedPrefs(appContext)?.let { return it }

        android.util.Log.e(
            TAG,
            "Шифрованное хранилище недоступно — сессия YClients живёт только до перезапуска",
        )
        return InMemoryPrefs()
    }

    private fun createEncryptedPrefs(appContext: Context): SharedPreferences? = try {
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
        android.util.Log.e(TAG, "EncryptedSharedPreferences failed: ${t.message}")
        null
    }

    /** Крайний fallback: значения только в памяти процесса, на диск ничего не пишется. */
    private class InMemoryPrefs : SharedPreferences {
        private val values = java.util.concurrent.ConcurrentHashMap<String, Any>()

        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?): String? =
            values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearFirst = false

            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, value: MutableSet<String>?) =
                apply { pending[key] = value?.toSet() }

            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { clearFirst = true }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearFirst) values.clear()
                for ((key, value) in pending) {
                    if (value == null) values.remove(key) else values[key] = value
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    companion object {
        private const val TAG = "TokenStorage"

        private const val PREFS_SECURE_NAME = "neiro_yclients_secure"

        /** Plaintext-fallback старых версий; удаляется, когда шифрование доступно. */
        private const val PREFS_LEGACY_FALLBACK_NAME = "neiro_yclients_fallback"

        private const val KEY_USER_TOKEN = "user_token"
        private const val KEY_USER_LOGIN = "user_login"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_AVATAR_URL = "user_avatar_url"
        private const val KEY_PARTNER_TOKEN = "partner_token"
        private const val KEY_COMPANY_ID = "company_id"
        private const val KEY_STAFF_ID = "staff_id"
        private const val KEY_STAFF_ID_COMPANY = "staff_id_company"

    }
}
