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
     *
     * Внутри проходит по всем страницам YClients (`page=1..N`), пока сервер не вернёт
     * страницу меньше [PAGE_SIZE] — иначе при большом числе занятий часть месяца
     * терялась бы из-за пагинации.
     */
    suspend fun getRecords(
        startDate: LocalDate,
        endDate: LocalDate,
    ): ApiResult<List<RecordData>> = withContext(Dispatchers.IO) {
        try {
            val formatter = DateTimeFormatter.ISO_LOCAL_DATE
            val start = startDate.format(formatter)
            val end = endDate.format(formatter)
            val all = mutableListOf<RecordData>()

            var page = 1
            while (page <= MAX_PAGES) {
                val response = api.getRecords(
                    companyId = tokenStorage.companyId,
                    startDate = start,
                    endDate = end,
                    staffId = tokenStorage.staffId,
                    page = page,
                    count = PAGE_SIZE,
                )

                if (!response.isSuccessful) {
                    return@withContext ApiResult.Error(
                        message = "Ошибка загрузки записей",
                        code = response.code(),
                    )
                }

                val body = response.body()
                if (body?.success != true) {
                    return@withContext ApiResult.Error("Не удалось получить записи")
                }

                val pageData = body.data.orEmpty()
                all += pageData
                if (pageData.size < PAGE_SIZE) break
                page++
            }

            ApiResult.Success(all)
        } catch (e: Exception) {
            ApiResult.Error("Ошибка сети: ${e.localizedMessage}")
        }
    }

    /**
     * Найти сотрудника филиала по имени из ответа /auth и сохранить его staffId.
     *
     * Используем публичный /book_staff (требует только partner_token), чтобы не натыкаться
     * на 403 от приватных эндпоинтов, если у текущего пользователя нет admin-прав.
     *
     * Эвристика поиска: совпадение имени пользователя из /auth с полем `name` сотрудника
     * (без учёта регистра и порядка слов). Это покрывает кейсы вроде
     * "Зеленкина Светлана Васильевна" vs "Светлана Зеленкина".
     */
    suspend fun detectAndSaveStaffId(): Int? = withContext(Dispatchers.IO) {
        val userName = tokenStorage.userName?.trim().orEmpty()
        if (userName.isBlank()) return@withContext null

        try {
            val response = api.getBookStaff(tokenStorage.companyId)
            val staffList = response.body()?.takeIf { it.success }?.data ?: return@withContext null

            val needleTokens = userName.normalizeNameTokens()
            val match = staffList.firstOrNull { staff ->
                val staffTokens = staff.name?.normalizeNameTokens() ?: emptySet()
                staffTokens.isNotEmpty() && needleTokens.all { it in staffTokens }
            }

            match?.id?.also { tokenStorage.staffId = it }
        } catch (e: Exception) {
            null
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

    private fun String.normalizeNameTokens(): Set<String> =
        lowercase()
            .replace('ё', 'е')
            .split(' ', '\t', '\n', '-', '.', ',')
            .map { it.trim() }
            .filter { it.length >= 3 }
            .toSet()

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
        /** Размер страницы для запроса /records. */
        private const val PAGE_SIZE = 200

        /** Предел постраничного обхода — защита от бесконечного цикла. */
        private const val MAX_PAGES = 50

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
