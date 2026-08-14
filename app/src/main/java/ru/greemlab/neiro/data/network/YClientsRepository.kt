package ru.greemlab.neiro.data.network

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import ru.greemlab.neiro.BuildConfig
import ru.greemlab.neiro.auth.LogoutCoordinator
import ru.greemlab.neiro.push.PushConfig
import ru.greemlab.neiro.push.PushDeviceId
import ru.greemlab.neiro.push.PushEventsCursor
import ru.greemlab.neiro.push.PushFcmToken
import ru.greemlab.neiro.sync.YClientsLiveSyncFormat

/**
 * Результат операции с API.
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>

    /**
     * @param offline запрос не дошёл до сервера Neiro. Отличается от отказа
     * сервера принципиально: показывать надо «нет связи, данные сохранённые», а
     * повторять — с паузой, а не на каждое движение пользователя (Этап 8).
     */
    data class Error(
        val message: String,
        val code: Int? = null,
        val offline: Boolean = false,
    ) : ApiResult<Nothing>
}

/**
 * Репозиторий данных YClients — через сервис Neiro на Pi.
 *
 * Ключей YClients у приложения больше нет: вход отдаёт `device_token`, а
 * записи, клиентов и зарплату отдаёт прокси, подставляя `company_id` и
 * `staff_id` сам (docs/neiro-push/ARCHITECTURE.md).
 */
class YClientsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val api = YClientsClient.getApi(context)
    private val neiroApi = YClientsClient.getNeiroApi(context)
    private val tokenStorage = YClientsClient.getTokenStorage(context)

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

    /** `user_token` на сервере протух: данные не идут, пока не введён пароль. */
    val reauthRequired: StateFlow<Boolean> = tokenStorage.reauthRequired

    val staffId: Int? get() = tokenStorage.staffId
    val userName: String? get() = tokenStorage.userName

    /** Логин прошлого входа — подставляется в форму, когда нужен повторный. */
    val userLogin: String? get() = tokenStorage.userLogin

    /**
     * Вход по логину и паролю YClients.
     *
     * Пароль уходит ровно в один запрос и на телефоне не сохраняется. Тот же
     * запрос регистрирует устройство: `device_id` и токен FCM едут вместе с
     * ним, отдельного вызова регистрации больше нет.
     *
     * Ждёт завершения хвоста logout после 401, если он ещё идёт — иначе его
     * финальные tokenStorage.clear()/clearSyncState() могли бы затереть только
     * что установленную новую сессию (гонка входа с 401-logout).
     *
     * Второй барьер той же гонки — [sessionGeneration]: `join()` не помогает
     * против 401, который ещё летит по сети и до logout пока не дошёл.
     */
    suspend fun login(login: String, password: String): ApiResult<NeiroAccount> {
        if (!PushConfig.isServerConfigured) {
            return ApiResult.Error(SERVER_NOT_CONFIGURED_MESSAGE)
        }
        logoutOn401Job?.join()
        return withContext(Dispatchers.IO) {
            // Токен FCM берём до входа: сервер регистрирует устройство тем же
            // запросом. Не дал — не беда, вход пройдёт и без пушей, а токен
            // донесёт PushRegistrar, когда Firebase его выдаст.
            val fcmToken = PushFcmToken.fetch().orEmpty()
            try {
                val response = neiroApi.login(
                    LoginRequest(
                        login = login,
                        password = password,
                        deviceId = PushDeviceId.get(appContext),
                        fcmToken = fcmToken,
                        label = Build.MODEL,
                        appVersion = BuildConfig.VERSION_NAME,
                    ),
                )

                if (!response.isSuccessful) {
                    return@withContext ApiResult.Error(
                        message = loginErrorMessage(
                            code = response.code(),
                            detail = errorDetail(response),
                            retryAfter = response.headers()["Retry-After"],
                        ),
                        code = response.code(),
                        offline = isUpstreamDown(response.code()),
                    )
                }

                val body = response.body()
                val deviceToken = body?.deviceToken
                if (deviceToken.isNullOrBlank()) {
                    return@withContext ApiResult.Error("Сервер не выдал ключ доступа")
                }

                val account = body.account ?: NeiroAccount()
                // Поколение поднимаем до записи токена: всё, что ушло в сеть
                // раньше, с этого момента считается хвостом прошлой сессии.
                sessionGeneration.incrementAndGet()
                tokenStorage.saveSession(
                    deviceToken = deviceToken,
                    staffId = account.staffId,
                    userLogin = login,
                    userName = account.userName,
                    avatarUrl = account.avatarUrl,
                )
                // Курсор событий: новое устройство стартует с конца журнала,
                // известное — со своего сохранённого места (API.md § Вход).
                PushEventsCursor.setIfAbsent(appContext, body.lastEventId)
                ApiResult.Success(account)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                ApiResult.Error(NETWORK_ERROR_MESSAGE, offline = true)
            } catch (e: Exception) {
                ApiResult.Error("Не удалось войти: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Состояние сессии на сервере: имя, аватар, `staff_id` и флаг «нужен
     * повторный вход». Спрашивается при запуске и при возврате в приложение —
     * без похода в YClients, поэтому дёшево.
     *
     * Молчит при любой ошибке сети: отсутствие связи с Pi не повод менять
     * локальное состояние. Ответ `401` — другое дело: доступ отозван.
     */
    suspend fun refreshSession(): Boolean = withContext(Dispatchers.IO) {
        if (tokenStorage.deviceToken == null) return@withContext false
        val generation = sessionGeneration.get()
        try {
            val response = neiroApi.session()
            if (!response.isSuccessful) {
                handleAuthFailure(response.code(), generation)
                return@withContext false
            }
            val body = response.body() ?: return@withContext false
            tokenStorage.setReauthRequired(body.reauthRequired)
            tokenStorage.updateAccount(
                staffId = body.account?.staffId,
                userName = body.account?.userName,
                avatarUrl = body.account?.avatarUrl,
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось обновить сессию: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Отзыв доступа этого устройства на сервере.
     *
     * Локальные данные чистит [logout] — порядок задаёт [LogoutCoordinator]:
     * сначала сеть (пока `device_token` ещё есть), потом хранилище. Не вышло —
     * токен откладывается: устройство на сервере иначе осталось бы живым и
     * продолжало получать пуши чужого теперь аккаунта.
     */
    suspend fun revokeDeviceOnServer(): Boolean {
        val token = tokenStorage.deviceToken ?: return true
        val revoked = revokeToken(token)
        tokenStorage.pendingRevokeToken = if (revoked) null else token
        return revoked
    }

    /** Повтор отложенного отзыва — при старте приложения. */
    suspend fun retryPendingRevoke() {
        val token = tokenStorage.pendingRevokeToken ?: return
        // Тот же токен мог снова стать текущим: повторный вход с того же
        // устройства выдаёт новый, но пока он не пришёл, отзывать нечего.
        if (token == tokenStorage.deviceToken) return
        if (revokeToken(token)) tokenStorage.pendingRevokeToken = null
    }

    private suspend fun revokeToken(token: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { neiroApi.logout("Bearer $token") }
            .onFailure { if (it is CancellationException) throw it }
            // 401 — токен и так недействителен: цель достигнута.
            .map { it.isSuccessful || it.code() == 401 }
            .getOrDefault(false)
    }

    /** Обновление токена FCM на сервере. */
    suspend fun updateFcmToken(fcmToken: String): Boolean = withContext(Dispatchers.IO) {
        if (tokenStorage.deviceToken == null || fcmToken.isBlank()) return@withContext false
        val generation = sessionGeneration.get()
        runCatching { neiroApi.updateFcmToken(FcmTokenRequest(fcmToken)) }
            .onFailure { if (it is CancellationException) throw it }
            .onSuccess { handleAuthFailure(it.code(), generation) }
            .map { it.isSuccessful }
            .getOrDefault(false)
    }

    /**
     * Выход из аккаунта: локальная часть.
     */
    fun logout() {
        tokenStorage.clear()
    }

    /**
     * Получить записи за период.
     *
     * Внутри проходит по всем страницам YClients (`page=1..N`), пока сервер не вернёт
     * страницу меньше [PAGE_SIZE] — иначе при большом числе занятий часть месяца
     * терялась бы из-за пагинации. Пагинация осталась на клиенте намеренно:
     * собирать страницы на сервере — переписывать работающий код.
     *
     * Чужих записей здесь быть не может: `staff_id` подставляет прокси, и
     * попросить расписание другого сотрудника нечем.
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
                    startDate = start,
                    endDate = end,
                    page = page,
                    count = PAGE_SIZE,
                    changedAfter = changedAfter,
                    withDeleted = if (changedAfter != null) 1 else null,
                )

                if (!response.isSuccessful) {
                    handleAuthFailure(response.code(), generation)
                    return ApiResult.Error(
                        message = requestErrorMessage(response, "Ошибка загрузки записей"),
                        code = response.code(),
                        offline = isUpstreamDown(response.code()),
                    )
                }

                val body = response.body()
                if (body?.success != true) {
                    return ApiResult.Error("Не удалось получить записи")
                }

                val pageData = body.data.orEmpty()
                fetchedCount += pageData.size
                body.meta?.totalCount?.let { totalCount = it }
                all += pageData.filter(::isValidRecord)
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
        } catch (e: IOException) {
            return ApiResult.Error(NETWORK_ERROR_MESSAGE, offline = true)
        } catch (e: Exception) {
            return ApiResult.Error("Ошибка загрузки записей: ${e.localizedMessage}")
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
     * Разбор двух отказов доступа — они означают разное (ARCHITECTURE.md
     * § Что происходит при отказах):
     *
     *  - `401` — `device_token` неизвестен или отозван: доступа больше нет,
     *    полный logout через [LogoutCoordinator] (остановка воркеров, сброс
     *    watermark'ов и состояния уведомлений);
     *  - `409` — протух `user_token` на сервере: аккаунт жив, устройство
     *    зарегистрировано, пуши идут — нужен только пароль. Сессию не гасим,
     *    поднимаем флаг «нужен повторный вход».
     */
    private fun handleAuthFailure(code: Int?, generation: Int) {
        if (code == 409) {
            if (generation == sessionGeneration.get()) tokenStorage.setReauthRequired(true)
            return
        }
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
                    page = page,
                    count = PAGE_SIZE,
                )

                if (!response.isSuccessful) {
                    handleAuthFailure(response.code(), generation)
                    return@withContext ApiResult.Error(
                        message = requestErrorMessage(response, "Ошибка загрузки клиентов"),
                        code = response.code(),
                        offline = isUpstreamDown(response.code()),
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
        } catch (e: IOException) {
            ApiResult.Error(NETWORK_ERROR_MESSAGE, offline = true)
        } catch (e: Exception) {
            ApiResult.Error("Ошибка загрузки клиентов: ${e.localizedMessage}")
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
        val periods = splitSalaryPeriods(from, to, today)
        if (periods.isEmpty()) return@withContext ApiResult.Success(emptyMap())

        val facts = mutableMapOf<LocalDate, DayFact>()
        var failure: ApiResult.Error? = null
        for (period in periods) {
            val chunk = requestSalaryDaily(
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
        from: LocalDate,
        to: LocalDate,
        today: LocalDate,
        generation: Int,
        allowRetry: Boolean,
    ): ApiResult<Map<LocalDate, DayFact>> {
        try {
            val response = api.getSalaryDaily(
                dateFrom = from.format(DateTimeFormatter.ISO_LOCAL_DATE),
                dateTo = to.format(DateTimeFormatter.ISO_LOCAL_DATE),
            )
            if (!response.isSuccessful) {
                handleAuthFailure(response.code(), generation)
                if (response.code() == 422 && allowRetry) {
                    // 422 — период залез в будущее или длиннее года. Обрезаем и пробуем один раз.
                    val trimmed = minOf(today, from.plusDays(364), to)
                    if (trimmed != to && !from.isAfter(trimmed)) {
                        return requestSalaryDaily(from, trimmed, today, generation, allowRetry = false)
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
            val envelope = SalaryEnvelope(response.body())
            if (!envelope.isSuccess) {
                val detail = envelope.message.orEmpty()
                Log.w(TAG, "salary_daily отказал: $detail")
                return ApiResult.Error(detail.ifBlank { "ответ без данных" })
            }
            val items = salaryDailyItems(envelope.data)
            if (items.isEmpty()) Log.w(TAG, "salary_daily: позиций не разобрано")
            return ApiResult.Success(parseDayFacts(items))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Зарплата: нет связи с сервером (${e.javaClass.simpleName})")
            return ApiResult.Error(NETWORK_ERROR_MESSAGE, offline = true)
        } catch (e: Exception) {
            Log.w(TAG, "Запрос зарплаты не удался", e)
            // Имя исключения нужно, чтобы отличить «ответ не разобрался»
            // (JsonSyntaxException) от прочих бед: чинить иначе приходится вслепую.
            return ApiResult.Error("${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(200))
        }
    }

    /**
     * Ставки каждого месяца из позиций его начисления.
     *
     * Единственный честный источник цены занятия. Делить начисление на число
     * услуг нельзя: в августе 2025 внутри 70 занятий по 1400 и 30 по 1250 (люди
     * доходили по старым абонементам), и деление даёт 1355 — число, которого
     * не существовало ни у одного занятия. В позициях же видно каждую ставку,
     * диагностика отличается названием, а интенсив — типом `activity`.
     *
     * Молчит при любой ошибке: не вышло — останется расчёт делением.
     */
    suspend fun fetchMonthlySalaryRates(
        from: LocalDate,
        to: LocalDate,
        today: LocalDate = LocalDate.now(),
    ): Map<YearMonth, SalaryRatesFromApi> = withContext(Dispatchers.IO) {
        val generation = sessionGeneration.get()
        val rates = mutableMapOf<YearMonth, SalaryRatesFromApi>()
        for (period in splitSalaryPeriods(from, to, today)) {
            val calculations = when (val result = requestCalculations(period, generation)) {
                is ApiResult.Success -> result.data
                is ApiResult.Error -> continue
            }
            for (calculation in calculations) {
                val id = calculation.id ?: continue
                // Непроведённое начисление ещё меняется — ставку из него не берём.
                if (!calculation.isConfirmed) continue
                val month = calculation.dateFrom?.take(7)?.let { raw ->
                    runCatching { YearMonth.parse(raw) }.getOrNull()
                } ?: continue
                if (rates.containsKey(month)) continue
                val monthRates = requestCalculationRates(id, generation)
                if (monthRates is ApiResult.Success && !monthRates.data.isEmpty) {
                    rates[month] = monthRates.data
                }
            }
        }
        rates
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
        val periods = splitSalaryPeriods(today.minusYears(1).plusDays(1), today, today)
        for (period in periods.reversed()) {
            val latest = requestLatestCalculation(period, generation)
            when (latest) {
                is ApiResult.Error -> return@withContext latest
                is ApiResult.Success -> {
                    val id = latest.data ?: continue
                    return@withContext requestCalculationRates(id, generation)
                }
            }
        }
        ApiResult.Success(SalaryRatesFromApi())
    }

    /** id самого свежего начисления периода или `null`, если начислений нет. */
    private suspend fun requestLatestCalculation(
        period: ClosedRange<LocalDate>,
        generation: Int,
    ): ApiResult<Long?> {
        val all = when (val result = requestCalculations(period, generation)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> return result
        }
        // Ставки берём из проведённого начисления: у закрытых месяцев
        // status = confirmed. Если проведённых нет — смотрим все, лучше
        // приблизительная ставка, чем никакой.
        val items = all.filter { it.isConfirmed }.ifEmpty { all }
        // Свежесть — по date_to; если его нет, берём наибольший id.
        val latest = items.maxWithOrNull(
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
    }

    /** Начисления периода: месяц, сумма, признак «проведено». */
    private suspend fun requestCalculations(
        period: ClosedRange<LocalDate>,
        generation: Int,
    ): ApiResult<List<SalaryCalculationSummary>> {
        try {
            val response = api.getSalaryCalculations(
                dateFrom = period.start.format(DateTimeFormatter.ISO_LOCAL_DATE),
                dateTo = period.endInclusive.format(DateTimeFormatter.ISO_LOCAL_DATE),
            )
            if (!response.isSuccessful) {
                handleAuthFailure(response.code(), generation)
                Log.w(TAG, "salary calculation list вернул HTTP ${response.code()}")
                return ApiResult.Error(salaryErrorMessage(response.code()), response.code())
            }
            return ApiResult.Success(salaryCalculations(SalaryEnvelope(response.body()).data))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Запрос списка начислений не удался", e)
            return ApiResult.Error("")
        }
    }

    private suspend fun requestCalculationRates(
        calculationId: Long,
        generation: Int,
    ): ApiResult<SalaryRatesFromApi> {
        try {
            val response = api.getSalaryCalculationDetails(calculationId = calculationId)
            if (!response.isSuccessful) {
                handleAuthFailure(response.code(), generation)
                Log.w(TAG, "salary calculation details вернул HTTP ${response.code()}")
                return ApiResult.Error(salaryErrorMessage(response.code()), response.code())
            }
            val envelope = SalaryEnvelope(response.body())
            return ApiResult.Success(extractSalaryRates(salaryCalculationItems(envelope.data)))
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
    private fun salaryErrorMessage(code: Int): String = when (code) {
        401 -> SESSION_GONE_MESSAGE
        409 -> REAUTH_MESSAGE
        else -> ""
    }

    /**
     * Текст отказа входа по коду сервиса (API.md § Вход и сессия).
     *
     * У `409` два разных повода, и советы у них противоположные, поэтому
     * различаем по `detail`: `staff_not_found` — чинится в YClients,
     * `device_taken` — этим телефоном уже владеет другой аккаунт, и чинить
     * его в YClients бессмысленно.
     */
    private fun loginErrorMessage(code: Int, detail: String?, retryAfter: String?): String =
        when (code) {
            401 -> "Неверный логин или пароль"
            409 -> when (detail) {
                "device_taken" ->
                    "Этот телефон уже привязан к другому аккаунту. Выйдите из " +
                        "него на этом устройстве или войдите тем же сотрудником."

                else -> "Имя в профиле YClients не совпало ни с одной карточкой " +
                    "сотрудника филиала. Исправьте имя в YClients и попробуйте снова."
            }

            429 -> "Слишком много попыток входа. " + retryAfterHint(retryAfter)
            502, 504 -> "YClients недоступен, попробуйте позже"
            else -> "Не удалось войти (ошибка $code)"
        }

    /**
     * Поле `detail` из тела отказа FastAPI. Тело читается один раз и только на
     * неуспешном ответе; распарсить не вышло — считаем, что различать нечем.
     */
    private fun errorDetail(response: retrofit2.Response<*>): String? = runCatching {
        val raw = response.errorBody()?.string().orEmpty()
        if (raw.isBlank()) return@runCatching null
        org.json.JSONObject(raw).optString("detail").takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * Текст отказа запроса данных. Отказ сервиса и отказ YClients различаются:
     * первый значит «доступ», второй — «данные», и советы у них разные.
     */
    private fun requestErrorMessage(response: retrofit2.Response<*>, fallback: String): String =
        when (response.code()) {
            401 -> SESSION_GONE_MESSAGE
            409 -> REAUTH_MESSAGE
            429 -> "Слишком часто. " + retryAfterHint(response.headers()["Retry-After"])
            502, 504 -> "YClients недоступен, попробуйте позже"
            else -> fallback
        }

    /**
     * Отказы, при которых данных не будет независимо от нас: прокси не достучался
     * до YClients (502) или не дождался ответа (504). Повторять такие имеет смысл
     * с той же паузой, что и отсутствие связи.
     */
    private fun isUpstreamDown(code: Int): Boolean = code == 502 || code == 504

    private fun retryAfterHint(retryAfter: String?): String {
        val seconds = retryAfter?.toLongOrNull() ?: return "Попробуйте позже."
        val minutes = (seconds + 59) / 60
        return if (minutes <= 1) "Попробуйте через минуту." else "Попробуйте через $minutes мин."
    }

    companion object {
        private const val TAG = "YClientsRepository"

        /** Pi не ответил: данные показываются из локального кэша (Этап 8). */
        const val NETWORK_ERROR_MESSAGE = "Нет связи с сервером Neiro"

        private const val SESSION_GONE_MESSAGE = "Доступ отозван. Войдите ещё раз."

        private const val REAUTH_MESSAGE = "Сессия YClients истекла. Войдите ещё раз."

        private const val SERVER_NOT_CONFIGURED_MESSAGE =
            "Сервер Neiro не настроен в сборке"

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
