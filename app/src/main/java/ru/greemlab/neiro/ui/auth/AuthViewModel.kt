package ru.greemlab.neiro.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.network.ApiResult
import ru.greemlab.neiro.data.network.YClientsRepository
import ru.greemlab.neiro.auth.LogoutCoordinator
import ru.greemlab.neiro.push.PushRegistrar

/**
 * Состояние экрана авторизации.
 */
data class AuthUiState(
    val login: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isLoggedIn: Boolean = false,
    val userName: String? = null,
    /** Вход есть, но `user_token` на сервере протух — нужен пароль. */
    val reauthRequired: Boolean = false,
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = YClientsRepository.getInstance(application)

    private val _uiState = MutableStateFlow(
        AuthUiState(
            // Логин прошлого входа: при повторном вводить его заново незачем.
            login = repository.userLogin.orEmpty(),
            isLoggedIn = repository.isLoggedIn.value,
            userName = repository.userName,
            reauthRequired = repository.reauthRequired.value,
        )
    )
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.isLoggedIn.collect { isLoggedIn ->
                _uiState.value = _uiState.value.copy(
                    isLoggedIn = isLoggedIn,
                    userName = repository.userName,
                )
            }
        }
        viewModelScope.launch {
            repository.reauthRequired.collect { required ->
                _uiState.value = _uiState.value.copy(reauthRequired = required)
            }
        }
    }

    fun updateLogin(login: String) {
        _uiState.value = _uiState.value.copy(login = login, error = null)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password, error = null)
    }

    fun login() {
        val state = _uiState.value
        // IME Done + повторный тап по кнопке иначе запускали бы два параллельных
        // repository.login (двойная регистрация устройства, гонка записи токена).
        if (state.isLoading) return
        val loginForRequest = normalizeLoginForRequest(state.login)

        if (loginForRequest.isBlank()) {
            _uiState.value = state.copy(error = "Введите логин")
            return
        }
        if (state.password.isBlank()) {
            _uiState.value = state.copy(error = "Введите пароль")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)

            when (val result = repository.login(loginForRequest, state.password)) {
                is ApiResult.Success -> {
                    // Устройство зарегистрировано тем же запросом — остаётся
                    // включить keepalive и догон событий.
                    PushRegistrar.onLoginSuccess(getApplication())

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isLoggedIn = true,
                        userName = result.data.userName,
                        password = "",
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message,
                    )
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            LogoutCoordinator.logout(getApplication())
            _uiState.value = _uiState.value.copy(
                isLoggedIn = false,
                userName = null,
                password = "",
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** Пароль не должен переживать уход с экрана (E7). */
    fun clearPassword() {
        _uiState.value = _uiState.value.copy(password = "")
    }

    override fun onCleared() {
        clearPassword()
    }

    private fun normalizeLoginForRequest(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (isLikelyEmail(trimmed) || !looksLikePhoneInput(trimmed)) return trimmed
        return normalizePhoneDigits(trimmed)
    }

    private fun isLikelyEmail(value: String): Boolean = value.contains('@')

    private fun looksLikePhoneInput(value: String): Boolean =
        value.all { it.isDigit() || it == '+' || it == '(' || it == ')' || it == '-' || it.isWhitespace() }

    private fun normalizePhoneDigits(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return ""

        val normalized = when {
            digits.length == 10 -> "7$digits"
            digits.length >= 11 && digits.startsWith("8") -> "7${digits.drop(1)}"
            else -> digits
        }

        return normalized.take(11)
    }
}
