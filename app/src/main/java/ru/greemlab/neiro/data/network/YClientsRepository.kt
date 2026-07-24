package ru.greemlab.neiro.data.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import ru.greemlab.neiro.auth.LogoutCoordinator
import ru.greemlab.neiro.sync.YClientsLiveSyncFormat

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

    private val appContext = context.applicationContext
    private val api = YClientsClient.getApi(context)
    private val tokenStorage = YClientsClient.getTokenStorage(context)
    private val errorGson = com.google.gson.Gson()

    private val logoutScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logoutOn401InProgress = AtomicBoolean(false)

    @Volatile
    private var logoutOn401Job: Job? = null

    val isLoggedIn: StateFlow<Boolean> = tokenStorage.isLoggedIn
    val userAvatarUrl: StateFlow<String?> = tokenStorage.userAvatarUrlFlow

    val companyId: Int get() = tokenStorage.companyId
    val staffId: Int? get() = tokenStorage.staffId
    val userName: String? get() = tokenStorage.userName
    val partnerToken: String get() = tokenStorage.partnerToken
    val userToken: String? get() = tokenStorage.userToken

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
     *
     * Ждёт завершения хвоста logout после 401, если он ещё идёт — иначе его
     * финальные tokenStorage.clear()/clearSyncState() могли бы затереть только
     * что установленную новую сессию (гонка входа с 401-logout).
     */
    suspend fun login(login: String, password: String): ApiResult<AuthData> {
        logoutOn401Job?.join()
        return withContext(Dispatchers.IO) {
            try {
                val response = api.auth(AuthRequest(login, password))

                if (response.isSuccessful) {
                    val body = response.body()
                    val userToken = body?.data?.userToken
                    if (body?.success == true && body.data != null && !userToken.isNullOrBlank()) {
                        tokenStorage.userToken = userToken
                        tokenStorage.userLogin = login
                        tokenStorage.userName = body.data.name
                        tokenStorage.userAvatarUrl =
                            normalizeYClientsAvatarUrl(body.data.avatar)
                        // Повторный вход другим сотрудником той же компании без полного
                        // logout иначе оставлял бы старый staffId — детект заново.
                        tokenStorage.staffId = null
                        detectAndSaveStaffId()
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ApiResult.Error("Ошибка сети: ${e.localizedMessage}")
            }
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
     *
     * Защита от «чужих» записей:
     *  - если `staffId` неизвестен (детект при логине не сработал) — пробуем
     *    определить его ещё раз; без `staff_id` API возвращает записи всех
     *    сотрудников филиала, и в календарь попадают чужие ученики;
     *  - дополнительно фильтруем результат на клиенте по `staffId`, даже если
     *    запрос ушёл с фильтром — это страхует от случаев, когда сервер
     *    игнорирует фильтр (например, особенности прав текущего user_token).
     */
    suspend fun getRecords(
        startDate: LocalDate,
        endDate: LocalDate,
    ): ApiResult<List<RecordData>> = withContext(Dispatchers.IO) {
        fetchRecords(startDate, endDate, changedAfter = null)
    }

    /**
     * Записи, созданные или изменённые после [changedAfter] (live-опрос).
     * Включает удалённые ([with_deleted]=1), чтобы пушить отмены.
     */
    suspend fun getRecordsChangedSince(
        changedAfter: Instant,
        startDate: LocalDate,
        endDate: LocalDate,
    ): ApiResult<List<RecordData>> = withContext(Dispatchers.IO) {
        fetchRecords(
            startDate = startDate,
            endDate = endDate,
            changedAfter = YClientsLiveSyncFormat.formatChangedAfter(changedAfter),
        )
    }

    private suspend fun fetchRecords(
        startDate: LocalDate,
        endDate: LocalDate,
        changedAfter: String?,
    ): ApiResult<List<RecordData>> {
        try {
            val effectiveStaffId = tokenStorage.staffId ?: detectAndSaveStaffId()
            if (effectiveStaffId == null) {
                return ApiResult.Error(
                    "Не удалось определить сотрудника YClients. " +
                        "Проверьте, что имя в вашем профиле YClients совпадает с " +
                        "карточкой сотрудника в филиале.",
                )
            }

            val formatter = DateTimeFormatter.ISO_LOCAL_DATE
            val start = startDate.format(formatter)
            val end = endDate.format(formatter)
            val all = mutableListOf<RecordData>()

            var page = 1
            var fetchedCount = 0
            var totalCount: Int? = null
            var complete = false
            while (page <= MAX_PAGES) {
                val response = api.getRecords(
                    companyId = tokenStorage.companyId,
                    startDate = start,
                    endDate = end,
                    staffId = effectiveStaffId,
                    page = page,
                    count = PAGE_SIZE,
                    changedAfter = changedAfter,
                    withDeleted = if (changedAfter != null) 1 else null,
                )

                if (!response.isSuccessful) {
                    handleUnauthorized(response.code())
                    return ApiResult.Error(
                        message = if (response.code() == 401) {
                            "Сессия истекла. Войдите ещё раз."
                        } else {
                            "Ошибка загрузки записей"
                        },
                        code = response.code(),
                    )
                }

                val body = response.body()
                if (body?.success != true) {
                    return ApiResult.Error("Не удалось получить записи")
                }

                val pageData = body.data.orEmpty()
                fetchedCount += pageData.size
                body.meta?.totalCount?.let { totalCount = it }
                all += pageData.filter { it.staffId == effectiveStaffId && isValidRecord(it) }
                if (pageData.size < PAGE_SIZE ||
                    totalCount?.let { fetchedCount >= it } == true
                ) {
                    complete = true
                    break
                }
                page++
            }

            if (!complete) {
                return ApiResult.Error(
                    "Слишком много записей за период — загрузка обрезана, календарь не изменён",
                )
            }

            return ApiResult.Success(all)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ApiResult.Error("Ошибка сети: ${e.localizedMessage}")
        }
    }

    /** Gson не проверяет Kotlin nullability — отбрасываем записи без обязательных полей. */
    private fun isValidRecord(record: RecordData): Boolean {
        @Suppress("SENSELESS_COMPARISON")
        val valid = record.date != null && record.id != 0L
        if (!valid) {
            Log.w(TAG, "Пропущена запись без id/date (id=${record.id})")
        }
        return valid
    }

    /**
     * 401 — полный logout через [LogoutCoordinator]: отзыв push-регистрации,
     * остановка воркеров, сброс watermark'ов и состояния уведомлений.
     */
    private fun handleUnauthorized(code: Int?) {
        if (code != 401) return
        tokenStorage.clear()
        if (logoutOn401InProgress.compareAndSet(false, true)) {
            logoutOn401Job = logoutScope.launch {
                try {
                    LogoutCoordinator.logout(appContext)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Logout после 401 завершился с ошибкой", e)
                } finally {
                    logoutOn401InProgress.set(false)
                }
            }
        }
    }

    /**
     * Найти сотрудника филиала по имени из ответа /auth и сохранить его staffId.
     *
     * Используем публичный /book_staff (требует только partner_token), чтобы не натыкаться
     * на 403 от приватных эндпоинтов, если у текущего пользователя нет admin-прав.
     *
     * Эвристика поиска: считаем пересечение нормализованных токенов имени
     * (см. [normalizeNameTokens]) и берём сотрудника с наибольшим совпадением,
     * но не менее **двух** общих токенов — иначе слишком велик риск ложного
     * матча по одному имени, когда в филиале несколько тёзок.
     *
     * Это устойчиво к асимметрии форматов:
     *  - `/auth` возвращает «Зеленкина Светлана Васильевна» (с отчеством);
     *  - `/book_staff` хранит «Светлана Зеленкина» (без отчества).
     * Прошлая версия требовала, чтобы **все** токены ника были у сотрудника,
     * и из-за лишнего «Васильевна» матч просто не находился. В результате
     * `staffId` оставался null, а API без фильтра возвращал расписание всех
     * сотрудников филиала.
     *
     * Также при равном счёте предпочитаем действующих сотрудников (`fired == 0`),
     * чтобы случайно не зацепиться за карточку уволенного тёзки.
     */
    suspend fun detectAndSaveStaffId(): Int? = withContext(Dispatchers.IO) {
        val userName = tokenStorage.userName?.trim().orEmpty()
        if (userName.isBlank()) return@withContext null

        try {
            val response = api.getBookStaff(tokenStorage.companyId)
            if (!response.isSuccessful) {
                handleUnauthorized(response.code())
                Log.w(TAG, "book_staff вернул HTTP ${response.code()}")
                return@withContext null
            }
            val staffList = response.body()?.takeIf { it.success }?.data
            if (staffList == null) {
                Log.w(TAG, "book_staff: пустой или неуспешный ответ")
                return@withContext null
            }

            val needleTokens = userName.normalizeNameTokens()
            if (needleTokens.isEmpty()) return@withContext null

            // Если у пользователя в профиле YClients только 1 токен (например, "Светлана"),
            // понизим требование, иначе совсем не найдём.
            val minScore = MIN_NAME_MATCH_SCORE.coerceAtMost(needleTokens.size)

            val match = staffList
                .mapNotNull { staff ->
                    val staffTokens = staff.name?.normalizeNameTokens() ?: emptySet()
                    if (staffTokens.isEmpty()) return@mapNotNull null
                    val score = (staffTokens intersect needleTokens).size
                    if (score < minScore) null else staff to score
                }
                .sortedWith(
                    compareByDescending<Pair<StaffData, Int>> { (it.first.fired ?: 0) == 0 }
                        .thenByDescending { it.second },
                )
                .firstOrNull()
                ?.first

            match?.id?.also { tokenStorage.staffId = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось определить сотрудника", e)
            null
        }
    }

    /**
     * Получить список клиентов.
     */
    suspend fun getClients(): ApiResult<List<ClientData>> = withContext(Dispatchers.IO) {
        try {
            val all = mutableListOf<ClientData>()
            var page = 1
            var totalCount: Int? = null
            var complete = false

            while (page <= MAX_PAGES) {
                val response = api.getClients(
                    companyId = tokenStorage.companyId,
                    page = page,
                    count = PAGE_SIZE,
                )

                if (!response.isSuccessful) {
                    handleUnauthorized(response.code())
                    return@withContext ApiResult.Error(
                        message = if (response.code() == 401) {
                            "Сессия истекла. Войдите ещё раз."
                        } else {
                            "Ошибка загрузки клиентов"
                        },
                        code = response.code(),
                    )
                }

                val body = response.body()
                if (body?.success != true) {
                    return@withContext ApiResult.Error("Не удалось получить клиентов")
                }

                val pageData = body.data.orEmpty()
                body.meta?.totalCount?.let { totalCount = it }
                all += pageData
                if (pageData.size < PAGE_SIZE ||
                    totalCount?.let { all.size >= it } == true
                ) {
                    complete = true
                    break
                }
                page++
            }

            if (!complete) {
                return@withContext ApiResult.Error(
                    "Слишком много клиентов — список обрезан",
                )
            }

            ApiResult.Success(all)
        } catch (e: CancellationException) {
            throw e
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

    private fun normalizeYClientsAvatarUrl(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> trimmed
        }
    }

    private fun parseErrorMessage(errorBody: String?): String? {
        if (errorBody.isNullOrBlank()) return null
        return try {
            val error = errorGson.fromJson(errorBody, ApiError::class.java)
            error.meta?.message
        } catch (e: Exception) {
            // Тело ответа не логируем: может содержать детали сессии.
            Log.w(TAG, "Cannot parse error body: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "YClientsRepository"

        /** Размер страницы для запроса /records. */
        private const val PAGE_SIZE = 200

        /** Предел постраничного обхода — защита от бесконечного цикла. */
        private const val MAX_PAGES = 50

        /**
         * Минимальное число совпавших токенов имени для матча сотрудника.
         * 2 — нужны минимум 2 общих токена (имя+фамилия), чтобы исключить
         * ложный матч по одному имени, когда в филиале несколько тёзок.
         */
        private const val MIN_NAME_MATCH_SCORE = 2

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
