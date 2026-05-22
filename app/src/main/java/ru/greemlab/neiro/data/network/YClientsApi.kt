package ru.greemlab.neiro.data.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
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
     * Получить информацию о конкретной записи.
     */
    @GET("record/{company_id}/{record_id}")
    suspend fun getRecord(
        @Path("company_id") companyId: Int,
        @Path("record_id") recordId: Long,
    ): Response<RecordsResponse>

    /**
     * Публичный список сотрудников филиала (используется виджетом онлайн-записи).
     * Доступен только с partner_token, без user_token.
     */
    @GET("book_staff/{company_id}")
    suspend fun getBookStaff(
        @Path("company_id") companyId: Int,
    ): Response<StaffResponse>
}
