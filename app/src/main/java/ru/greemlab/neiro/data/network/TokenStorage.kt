package ru.greemlab.neiro.data.network

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Безопасное хранение сессии сервиса Neiro.
 *
 * Токенов YClients здесь больше нет: `partner_token` и `user_token` живут на
 * сервере (docs/neiro-push/ARCHITECTURE.md § Кто что хранит). На телефоне
 * остаётся `device_token` — ключ к своему же аккаунту, узкий и отзываемый.
 *
 * Данные переживают перезапуск приложения и обновление APK поверх существующей установки.
 */
class TokenStorage(context: Context) {

    private val prefs: SharedPreferences = createSecurePrefs(context.applicationContext)

    init {
        dropLegacyYClientsTokens()
    }

    private val _isLoggedIn =
        MutableStateFlow(!prefs.getString(KEY_DEVICE_TOKEN, null).isNullOrBlank())
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _userAvatarUrlFlow = MutableStateFlow(prefs.getString(KEY_USER_AVATAR_URL, null))
    val userAvatarUrlFlow: StateFlow<String?> = _userAvatarUrlFlow.asStateFlow()

    private val _reauthRequired = MutableStateFlow(prefs.getBoolean(KEY_REAUTH_REQUIRED, false))

    /** `user_token` на сервере протух: аккаунт и пуши живы, нужен пароль. */
    val reauthRequired: StateFlow<Boolean> = _reauthRequired.asStateFlow()

    /** Пишется только целиком вместе с остальной сессией — см. [saveSession]. */
    val deviceToken: String?
        get() = prefs.getString(KEY_DEVICE_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** Логин прошлого входа — подсказка в форме, когда нужен повторный вход. */
    val userLogin: String?
        get() = prefs.getString(KEY_USER_LOGIN, null)

    val userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)

    val userAvatarUrl: String?
        get() = prefs.getString(KEY_USER_AVATAR_URL, null)

    /**
     * Свой `staff_id` в YClients: приходит с сервера при входе, телефон его
     * больше не вычисляет. Нужен для отсева чужих событий из пуша и догона.
     */
    val staffId: Int?
        get() = prefs.getInt(KEY_STAFF_ID, -1).takeIf { it > 0 }

    /**
     * Сессия целиком — одной записью: `device_token` и справочные поля аккаунта
     * приезжают из одного ответа `/v1/auth/login` и разъезжаться не должны.
     */
    fun saveSession(
        deviceToken: String,
        staffId: Int?,
        userLogin: String?,
        userName: String?,
        avatarUrl: String?,
    ) {
        val normalizedAvatar = avatarUrl?.trim()?.takeIf { it.isNotBlank() }
        prefs.edit {
            putString(KEY_DEVICE_TOKEN, deviceToken)
            putString(KEY_USER_LOGIN, userLogin?.trim()?.takeIf { it.isNotBlank() })
            putString(KEY_USER_NAME, userName?.trim()?.takeIf { it.isNotBlank() })
            putString(KEY_USER_AVATAR_URL, normalizedAvatar)
            if (staffId != null && staffId > 0) putInt(KEY_STAFF_ID, staffId) else remove(KEY_STAFF_ID)
            remove(KEY_REAUTH_REQUIRED)
        }
        _userAvatarUrlFlow.value = normalizedAvatar
        _reauthRequired.value = false
        _isLoggedIn.value = true
    }

    /**
     * Токен вышедшего устройства, который не удалось отозвать на сервере.
     *
     * Хранится отдельно от сессии и переживает [clear]: пока отзыв не прошёл,
     * устройство на сервере живо и продолжает получать пуши чужого теперь
     * аккаунта. Повтор — при следующем старте приложения.
     */
    var pendingRevokeToken: String?
        get() = prefs.getString(KEY_PENDING_REVOKE, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit { putString(KEY_PENDING_REVOKE, value?.takeIf { it.isNotBlank() }) }
        }

    /**
     * Справочные поля аккаунта из `GET /v1/session`.
     *
     * `device_token` не трогает: сессия остаётся той же, обновляется только то,
     * что сервер мог поменять — имя, аватар, `staff_id`. Пустые значения
     * пропускаются: неполный ответ не должен стирать то, что уже известно.
     */
    fun updateAccount(staffId: Int?, userName: String?, avatarUrl: String?) {
        val normalizedAvatar = avatarUrl?.trim()?.takeIf { it.isNotBlank() }
        prefs.edit {
            if (staffId != null && staffId > 0) putInt(KEY_STAFF_ID, staffId)
            userName?.trim()?.takeIf { it.isNotBlank() }?.let { putString(KEY_USER_NAME, it) }
            normalizedAvatar?.let { putString(KEY_USER_AVATAR_URL, it) }
        }
        if (normalizedAvatar != null) _userAvatarUrlFlow.value = normalizedAvatar
    }

    /**
     * Флаг «нужен повторный вход паролем» ([reauthRequired]).
     *
     * Сессию не гасит: `device_token` остаётся рабочим, устройство на сервере
     * зарегистрировано, пуши продолжают идти — не хватает только пароля
     * (ARCHITECTURE.md § Что происходит при отказах).
     */
    fun setReauthRequired(required: Boolean) {
        prefs.edit { putBoolean(KEY_REAUTH_REQUIRED, required) }
        _reauthRequired.value = required
    }

    fun clear() {
        prefs.edit {
            remove(KEY_DEVICE_TOKEN)
            remove(KEY_USER_LOGIN)
            remove(KEY_USER_NAME)
            remove(KEY_USER_AVATAR_URL)
            remove(KEY_STAFF_ID)
            remove(KEY_REAUTH_REQUIRED)
        }
        _isLoggedIn.value = false
        _userAvatarUrlFlow.value = null
        _reauthRequired.value = false
    }

    /**
     * Токены YClients из версий до перехода на прокси.
     *
     * Обновление ставится поверх существующей установки, и оба секрета иначе
     * так и остались бы лежать на телефоне — ровно то, ради чего затевался
     * переезд на сервер. Ключ `partner_token` в шифрованных prefs жил и без
     * входа: чистим при каждом старте, не только при первом.
     */
    private fun dropLegacyYClientsTokens() {
        val stale = LEGACY_KEYS.filter { prefs.contains(it) }
        if (stale.isEmpty()) return
        prefs.edit { stale.forEach(::remove) }
        android.util.Log.i(TAG, "Удалены токены YClients прошлых версий: ${stale.size}")
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

        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_USER_LOGIN = "user_login"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_AVATAR_URL = "user_avatar_url"
        private const val KEY_STAFF_ID = "staff_id"
        private const val KEY_REAUTH_REQUIRED = "reauth_required"
        private const val KEY_PENDING_REVOKE = "pending_revoke_token"

        /** Наследство версий, ходивших в YClients напрямую (см. [dropLegacyYClientsTokens]). */
        private val LEGACY_KEYS = listOf(
            "user_token",
            "partner_token",
            "company_id",
            "staff_id_company",
        )
    }
}
