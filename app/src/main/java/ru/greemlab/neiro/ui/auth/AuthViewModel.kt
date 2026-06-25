package ru.greemlab.neiro.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.greemlab.neiro.BuildConfig
import ru.greemlab.neiro.data.network.ApiResult
import ru.greemlab.neiro.data.network.YClientsRepository
import ru.greemlab.neiro.push.PushRegistrar

private fun defaultCompanyId(): String {
    val fromBuildConfig = BuildConfig.YCLIENTS_COMPANY_ID
    return if (fromBuildConfig > 0) fromBuildConfig.toString() else ""
}

/**
 * Состояние экрана авторизации.
 */
data class AuthUiState(
    val login: String = "",
    val password: String = "",
    val partnerToken: String = "",
    val companyId: String = defaultCompanyId(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isLoggedIn: Boolean = false,
    val userName: String? = null,
    val showPartnerTokenSetup: Boolean = false,
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = YClientsRepository.getInstance(application)

    private val _uiState = MutableStateFlow(
        AuthUiState(
            partnerToken = repository.partnerToken,
            companyId = repository.companyId.toString(),
            isLoggedIn = repository.isLoggedIn.value,
            userName = repository.userName,
            // Если токен уже зашит через BuildConfig — не показываем настройки.
            showPartnerTokenSetup = false,
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
    }

    fun updateLogin(login: String) {
        _uiState.value = _uiState.value.copy(login = login, error = null)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password, error = null)
    }

    fun updatePartnerToken(token: String) {
        _uiState.value = _uiState.value.copy(partnerToken = token, error = null)
    }

    fun updateCompanyId(id: String) {
        _uiState.value = _uiState.value.copy(companyId = id, error = null)
    }

    fun showPartnerTokenSetup() {
        _uiState.value = _uiState.value.copy(showPartnerTokenSetup = true)
    }

    fun hidePartnerTokenSetup() {
        _uiState.value = _uiState.value.copy(showPartnerTokenSetup = false)
    }

    fun savePartnerSettings() {
        val state = _uiState.value
        val token = state.partnerToken.trim()
        val companyId = state.companyId.toIntOrNull()

        if (token.isBlank()) {
            _uiState.value = state.copy(error = "Введите Partner Token")
            return
        }
        if (companyId == null || companyId <= 0) {
            _uiState.value = state.copy(error = "Введите корректный ID компании")
            return
        }

        repository.setPartnerToken(token)
        repository.setCompanyId(companyId)
        _uiState.value = state.copy(
            showPartnerTokenSetup = false,
            error = null,
        )
    }

    fun login() {
        val state = _uiState.value
        val loginForRequest = normalizeLoginForRequest(state.login)

        if (!repository.hasPartnerToken()) {
            _uiState.value = state.copy(
                error = "Partner Token не настроен. Проверьте local.properties и пересоберите приложение.",
                showPartnerTokenSetup = true,
            )
            return
        }

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
                    // Параллельно ищем staff_id текущего пользователя в списке сотрудников филиала.
                    // Если не нашли — не фатально, расписание просто будет без фильтра по сотруднику.
                    repository.detectAndSaveStaffId()

                    PushRegistrar.onLoginSuccess(getApplication())

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isLoggedIn = true,
                        userName = result.data.name,
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
        PushRegistrar.onLogout(getApplication())
        repository.logout()
        _uiState.value = _uiState.value.copy(
            isLoggedIn = false,
            userName = null,
            password = "",
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
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
