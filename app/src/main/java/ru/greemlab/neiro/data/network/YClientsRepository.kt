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
import java.util.concurrent.atomic.AtomicInteger
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

    /**
     * Номер текущей сессии: растёт на каждом успешном входе. Запрос запоминает его
     * перед отправкой, и 401 гасит сессию только если она та же самая — иначе
     * ответ запроса из прошлой сессии, доехавший уже после нового входа, стирал бы
     * свежие токены.
     */
    private val sessionGeneration = AtomicInteger(0)

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
     *
     * Второй барьер той же гонки — [sessionGeneration]: `join()` не помогает
     * против 401, который ещё летит по сети и до logout пока не дошёл.
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
                        // Поколение поднимаем до записи токенов: всё, что ушло в сеть
                        // раньше, с этого момента считается хвостом прошлой сессии.
                        sessionGeneration.incrementAndGet()
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
        val generation = sessionGeneration.get()
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
                    handleUnauthorized(response.code(), generation)
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
    private fun handleUnauthorized(code: Int?, generation: Int) {
        if (code != 401) return
        // Хвост прошлой сессии: пользователь уже вошёл заново, пока запрос был в пути.
        // Гасить новую сессию из-за него нельзя — иначе вход «отменяется» сам собой.
        if (generation != sessionGeneration.get()) {
            Log.w(TAG, "401 от запроса прошлой сессии — новую не трогаем")
            return
        }
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

        val generation = sessionGeneration.get()
        try {
            val response = api.getBookStaff(tokenStorage.companyId)
            if (!response.isSuccessful) {
                handleUnauthorized(response.code(), generation)
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
        val generation = sessionGeneration.get()
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
                    handleUnauthorized(response.code(), generation)
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

    /**
     * Начисленная ЗП по дням за период (FOUNDATION 3.4).
     *
     * Период режется под ограничения YClients ([splitSalaryPeriods]), поэтому
     * будущее сюда не уходит. Ошибки денежных запросов пользователю не
     * показываются: при 403 и офлайне расчёт просто остаётся по цене профиля
     * (FOUNDATION 3.5).
     */
    suspend fun fetchSalaryDaily(
        from: LocalDate,
        to: LocalDate,
        today: LocalDate = LocalDate.now(),
    ): ApiResult<Map<LocalDate, DayFact>> = withContext(Dispatchers.IO) {
        val generation = sessionGeneration.get()
        val staff = tokenStorage.staffId ?: detectAndSaveStaffId()
        if (staff == null) return@withContext ApiResult.Error(STAFF_UNKNOWN_MESSAGE)

        val periods = splitSalaryPeriods(from, to, today)
        if (periods.isEmpty()) return@withContext ApiResult.Success(emptyMap())

        val facts = mutableMapOf<LocalDate, DayFact>()
        var failure: ApiResult.Error? = null
        for (period in periods) {
            val chunk = requestSalaryDaily(
                staffId = staff,
                from = period.start,
                to = period.endInclusive,
                today = today,
                generation = generation,
                allowRetry = true,
            )
            when (chunk) {
                // Год, по которому не ответили, не должен уносить с собой
                // остальные: период режется по календарным годам, и первым
                // куском идёт самый старый — тот, где сотрудника могло ещё не
                // быть. Раньше его ошибка обрывала всю историю на первом же шаге.
                is ApiResult.Error -> {
                    // 401 — сессия мертва, дальнейшие куски осмысленного не дадут.
                    if (chunk.code == 401) return@withContext chunk
                    if (failure == null) failure = chunk
                }

                is ApiResult.Success -> facts += chunk.data
            }
        }
        // Ошибку отдаём, только если не собрали вообще ничего: иначе половина
        // истории лучше, чем ничего, а недостающие годы дотянутся потом.
        failure?.takeIf { facts.isEmpty() } ?: ApiResult.Success(facts)
    }

    private suspend fun requestSalaryDaily(
        staffId: Int,
        from: LocalDate,
        to: LocalDate,
        today: LocalDate,
        generation: Int,
        allowRetry: Boolean,
    ): ApiResult<Map<LocalDate, DayFact>> {
        try {
            val response = api.getSalaryDaily(
                companyId = tokenStorage.companyId,
                staffId = staffId,
                dateFrom = from.format(DateTimeFormatter.ISO_LOCAL_DATE),
                dateTo = to.format(DateTimeFormatter.ISO_LOCAL_DATE),
            )
            if (!response.isSuccessful) {
                handleUnauthorized(response.code(), generation)
                if (response.code() == 422 && allowRetry) {
                    // 422 — период залез в будущее или длиннее года. Обрезаем и пробуем один раз.
                    val trimmed = minOf(today, from.plusDays(364), to)
                    if (trimmed != to && !from.isAfter(trimmed)) {
                        return requestSalaryDaily(staffId, from, trimmed, today, generation, allowRetry = false)
                    }
                }
                // Текст ошибки YClients — единственная подсказка, почему не отдал:
                // на 422 там осмысленное объяснение про параметры (API-HOWTO 1.3).
                val detail = runCatching { response.errorBody()?.string() }.getOrNull()
                Log.w(TAG, "salary_daily вернул HTTP ${response.code()}: $detail")
                return ApiResult.Error(
                    salaryErrorMessage(response.code()).ifBlank { detail.orEmpty().take(200) },
                    response.code(),
                )
            }
            val body = response.body()
            if (body?.success != true) {
                val detail = body?.meta?.message.orEmpty()
                Log.w(TAG, "salary_daily: success=${body?.success}, meta=$detail")
                return ApiResult.Error(detail.ifBlank { "ответ без данных" })
            }
            return ApiResult.Success(parseDayFacts(body.data))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Запрос зарплаты не удался", e)
            // Имя исключения различает «нет сети» (IOException) и «ответ не
            // разобрался» (JsonSyntaxException) — без него обе беды выглядят
            // одинаково, и чинить приходится вслепую.
            return ApiResult.Error("${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(200))
        }
    }

    /**
     * Ставки из детализации последнего закрытого начисления (FOUNDATION 6.2).
     *
     * Начисление создаётся в конце месяца, поэтому за текущий месяц список
     * пуст — идём по кускам периода от свежего к старому до первого непустого.
     */
    suspend fun fetchLatestSalaryRates(
        today: LocalDate = LocalDate.now(),
    ): ApiResult<SalaryRatesFromApi> = withContext(Dispatchers.IO) {
        val generation = sessionGeneration.get()
        val staff = tokenStorage.staffId ?: detectAndSaveStaffId()
        if (staff == null) return@withContext ApiResult.Error(STAFF_UNKNOWN_MESSAGE)

        val periods = splitSalaryPeriods(today.minusYears(1).plusDays(1), today, today)
        for (period in periods.reversed()) {
            val latest = requestLatestCalculation(staff, period, generation)
            when (latest) {
                is ApiResult.Error -> return@withContext latest
                is ApiResult.Success -> {
                    val id = latest.data ?: continue
                    return@withContext requestCalculationRates(staff, id, generation)
                }
            }
        }
        ApiResult.Success(SalaryRatesFromApi())
    }

    /** id самого свежего начисления периода или `null`, если начислений нет. */
    private suspend fun requestLatestCalculation(
        staffId: Int,
        period: ClosedRange<LocalDate>,
        generation: Int,
    ): ApiResult<Long?> {
        try {
            val response = api.getSalaryCalculations(
                companyId = tokenStorage.companyId,
                staffId = staffId,
                dateFrom = period.start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                dateTo = period.endInclusive.format(DateTimeFormatter.ISO_LOCAL_DATE),
            )
            if (!response.isSuccessful) {
                handleUnauthorized(response.code(), generation)
                Log.w(TAG, "salary calculation list вернул HTTP ${response.code()}")
                return ApiResult.Error(salaryErrorMessage(response.code()), response.code())
            }
            val items = response.body()?.data.orEmpty()
            // Свежесть — по date_to; если его нет, берём наибольший id.
            val latest = items
                .filter { it.id != null }
                .maxWithOrNull(
                    compareBy<SalaryCalculationSummary>(
                        { summary ->
                            summary.dateTo?.take(10)?.let { raw ->
                                runCatching { LocalDate.parse(raw) }.getOrNull()
                            }
                        },
                        { summary -> summary.id },
                    ),
                )
            return ApiResult.Success(latest?.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Запрос зарплаты не удался", e)
            return ApiResult.Error("")
        }
    }

    private suspend fun requestCalculationRates(
        staffId: Int,
        calculationId: Long,
        generation: Int,
    ): ApiResult<SalaryRatesFromApi> {
        try {
            val response = api.getSalaryCalculationDetails(
                companyId = tokenStorage.companyId,
                staffId = staffId,
                calculationId = calculationId,
            )
            if (!response.isSuccessful) {
                handleUnauthorized(response.code(), generation)
                Log.w(TAG, "salary calculation details вернул HTTP ${response.code()}")
                return ApiResult.Error(salaryErrorMessage(response.code()), response.code())
            }
            return ApiResult.Success(extractSalaryRates(response.body()?.data))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Запрос зарплаты не удался", e)
            return ApiResult.Error("")
        }
    }

    private fun parseDayFacts(items: List<SalaryDailyItem>?): Map<LocalDate, DayFact> {
        val facts = mutableMapOf<LocalDate, DayFact>()
        for (item in items.orEmpty()) {
            val date = item.date?.take(10)?.let { raw ->
                runCatching { LocalDate.parse(raw) }.getOrNull()
            } ?: continue
            val calculation = item.calculation ?: continue
            val salary = calculation.salary.toMoneyOrNull() ?: continue
            facts[date] = DayFact(
                salary = salary,
                servicesCount = calculation.servicesCount ?: 0,
                groupServicesCount = calculation.groupServicesCount ?: 0,
            )
        }
        return facts
    }

    /**
     * Текст ошибки денежного запроса. Пустая строка — «пользователю не
     * показывать»: 403 у сотрудника без прав владельца это штатный случай,
     * а не поломка (FOUNDATION 3.5).
     */
    private fun salaryErrorMessage(code: Int): String =
        if (code == 401) "Сессия истекла. Войдите ещё раз." else ""

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

        private const val STAFF_UNKNOWN_MESSAGE =
            "Не удалось определить сотрудника YClients"

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
