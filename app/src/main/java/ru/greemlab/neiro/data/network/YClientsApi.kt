package ru.greemlab.neiro.data.network

import com.google.gson.JsonElement
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * YClients REST API.
 *
 * Документация: https://developer.yclients.com/ru/
 *
 * Все методы требуют заголовок Authorization с partner_token.
 * Методы с записями/клиентами также требуют user_token.
 */
interface YClientsApi {

    /**
     * Авторизация пользователя.
     * Возвращает user_token для последующих запросов.
     */
    @POST("auth")
    suspend fun auth(
        @Body request: AuthRequest,
    ): Response<AuthResponse>

    /**
     * Получить список записей (расписание) для компании.
     *
     * @param companyId ID компании (из URL: /timetable/{companyId})
     * @param startDate Начало периода (YYYY-MM-DD)
     * @param endDate Конец периода (YYYY-MM-DD)
     * @param staffId ID сотрудника (опционально)
     * @param page Номер страницы
     * @param count Записей на странице
     */
    @GET("records/{company_id}")
    suspend fun getRecords(
        @Path("company_id") companyId: Int,
        @Query("start_date") startDate: String,
        @Query("end_date") endDate: String,
        @Query("staff_id") staffId: Int? = null,
        @Query("page") page: Int = 1,
        @Query("count") count: Int = 100,
        @Query("changed_after") changedAfter: String? = null,
        /** 1 — включить удалённые записи (нужно для live-опроса с [changedAfter]). */
        @Query("with_deleted") withDeleted: Int? = null,
    ): Response<RecordsResponse>

    /**
     * Получить список клиентов компании.
     *
     * @param companyId ID компании
     * @param page Номер страницы
     * @param count Клиентов на странице
     */
    @GET("clients/{company_id}")
    suspend fun getClients(
        @Path("company_id") companyId: Int,
        @Query("page") page: Int = 1,
        @Query("count") count: Int = 200,
    ): Response<ClientsResponse>

    /**
     * Публичный список сотрудников филиала (используется виджетом онлайн-записи).
     * Доступен только с partner_token, без user_token.
     */
    @GET("book_staff/{company_id}")
    suspend fun getBookStaff(
        @Path("company_id") companyId: Int,
    ): Response<StaffResponse>

    /**
     * Начисленная ЗП по каждому дню периода (API-HOWTO 5.1).
     *
     * Ограничения: будущее не считается, диапазон в будущее отбивается целиком
     * (422); эндпоинт «владельческий», у сотрудника без прав ожидаем 403.
     */
    @GET("company/{company_id}/salary/period/staff/daily/{staff_id}/")
    suspend fun getSalaryDaily(
        @Path("company_id") companyId: Int,
        @Path("staff_id") staffId: Int,
        @Query("date_from") dateFrom: String,
        @Query("date_to") dateTo: String,
    ): Response<JsonElement>

    /**
     * Список закрытых начислений за период (месяц → id, сумма).
     * Период больше года отбивается с 422.
     */
    @GET("company/{company_id}/salary/payroll/staff/{staff_id}/calculation/")
    suspend fun getSalaryCalculations(
        @Path("company_id") companyId: Int,
        @Path("staff_id") staffId: Int,
        @Query("date_from") dateFrom: String,
        @Query("date_to") dateTo: String,
    ): Response<JsonElement>

    /**
     * Детализация начисления: каждая позиция со своей ставкой (API-HOWTO 5.2).
     * Единственный источник ставок по видам работ.
     */
    @GET("company/{company_id}/salary/payroll/staff/{staff_id}/calculation/{calculation_id}")
    suspend fun getSalaryCalculationDetails(
        @Path("company_id") companyId: Int,
        @Path("staff_id") staffId: Int,
        @Path("calculation_id") calculationId: Long,
    ): Response<JsonElement>
}
