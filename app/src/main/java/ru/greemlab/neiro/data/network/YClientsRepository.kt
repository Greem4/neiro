package ru.greemlab.neiro.data.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Результат операции с API.
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>
    data class Error(val message: String, val code: Int? = null) : ApiResult<Nothing>
}

/**
 * Репозиторий для работы с YClients API.
 */
class YClientsRepository(context: Context) {

    private val api = YClientsClient.getApi(context)
    private val tokenStorage = YClientsClient.getTokenStorage(context)

    val isLoggedIn: StateFlow<Boolean> = tokenStorage.isLoggedIn

    val companyId: Int get() = tokenStorage.companyId
    val staffId: Int? get() = tokenStorage.staffId
    val userName: String? get() = tokenStorage.userName
    val partnerToken: String get() = tokenStorage.partnerToken

    fun hasPartnerToken(): Boolean = tokenStorage.partnerToken.isNotBlank()

    fun setPartnerToken(token: String) {
        tokenStorage.partnerToken = token
        YClientsClient.clearInstance()
    }

    fun setCompanyId(id: Int) {
        tokenStorage.companyId = id
    }

    /**
     * Авторизация пользователя.
     */
    suspend fun login(login: String, password: String): ApiResult<AuthData> =
        withContext(Dispatchers.IO) {
            try {
                val response = api.auth(AuthRequest(login, password))

                if (response.isSuccessful) {
                    val body = response.body()
                    if (body?.success == true && body.data != null) {
                        tokenStorage.userToken = body.data.userToken
                        tokenStorage.userLogin = login
                        tokenStorage.userName = body.data.name
                        ApiResult.Success(body.data)
                    } else {
                        ApiResult.Error("Неверный логин или пароль")
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    ApiResult.Error(
                        message = parseErrorMessage(errorBody) ?: "Ошибка авторизации",
                        code = response.code(),
                    )
                }
            } catch (e: Exception) {
                ApiResult.Error("Ошибка сети: ${e.localizedMessage}")
            }
        }

    /**
     * Выход из аккаунта.
     */
    fun logout() {
        tokenStorage.clear()
    }

    /**
     * Получить записи за период.
     */
    suspend fun getRecords(
        startDate: LocalDate,
        endDate: LocalDate,
    ): ApiResult<List<RecordData>> = withContext(Dispatchers.IO) {
        try {
            val formatter = DateTimeFormatter.ISO_LOCAL_DATE
            val response = api.getRecords(
                companyId = tokenStorage.companyId,
                startDate = startDate.format(formatter),
                endDate = endDate.format(formatter),
                staffId = tokenStorage.staffId,
            )

            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true) {
                    ApiResult.Success(body.data.orEmpty())
                } else {
                    ApiResult.Error("Не удалось получить записи")
                }
            } else {
                ApiResult.Error(
                    message = "Ошибка загрузки записей",
                    code = response.code(),
                )
            }
        } catch (e: Exception) {
            ApiResult.Error("Ошибка сети: ${e.localizedMessage}")
        }
    }

    /**
     * Получить список клиентов.
     */
    suspend fun getClients(): ApiResult<List<ClientData>> = withContext(Dispatchers.IO) {
        try {
            val response = api.getClients(
                companyId = tokenStorage.companyId,
            )

            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true) {
                    ApiResult.Success(body.data.orEmpty())
                } else {
                    ApiResult.Error("Не удалось получить клиентов")
                }
            } else {
                ApiResult.Error(
                    message = "Ошибка загрузки клиентов",
                    code = response.code(),
                )
            }
        } catch (e: Exception) {
            ApiResult.Error("Ошибка сети: ${e.localizedMessage}")
        }
    }

    private fun parseErrorMessage(errorBody: String?): String? {
        if (errorBody.isNullOrBlank()) return null
        return try {
            val gson = com.google.gson.Gson()
            val error = gson.fromJson(errorBody, ApiError::class.java)
            error.meta?.message
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        @Volatile
        private var instance: YClientsRepository? = null

        fun getInstance(context: Context): YClientsRepository {
            return instance ?: synchronized(this) {
                instance ?: YClientsRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
