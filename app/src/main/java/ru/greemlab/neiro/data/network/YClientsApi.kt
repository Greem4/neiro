package ru.greemlab.neiro.data.network

import com.google.gson.JsonElement
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Данные YClients через прокси сервиса Neiro (docs/neiro-push/API.md § Прокси).
 *
 * Напрямую в api.yclients.com приложение больше не ходит: `partner_token` и
 * `user_token` остались на сервере. Оттуда же подставляются `company_id` и
 * `staff_id` — в запросе их нет вовсе, поэтому попросить чужие записи нечем.
 *
 * Тело ответа YClients прокси отдаёт как есть, вместе с его кодом, — модели и
 * разбор в приложении те же, что и раньше.
 */
interface YClientsApi {

    /**
     * Записи (расписание) своего сотрудника за период.
     *
     * @param startDate Начало периода (YYYY-MM-DD)
     * @param endDate Конец периода (YYYY-MM-DD)
     * @param page Номер страницы
     * @param count Записей на странице
     */
    @GET("v1/yclients/records")
    suspend fun getRecords(
        @Query("start_date") startDate: String,
        @Query("end_date") endDate: String,
        @Query("page") page: Int = 1,
        @Query("count") count: Int = 100,
        @Query("changed_after") changedAfter: String? = null,
        /** 1 — включить удалённые записи (нужно для live-опроса с [changedAfter]). */
        @Query("with_deleted") withDeleted: Int? = null,
    ): Response<RecordsResponse>

    /**
     * Клиенты филиала.
     *
     * @param page Номер страницы
     * @param count Клиентов на странице
     */
    @GET("v1/yclients/clients")
    suspend fun getClients(
        @Query("page") page: Int = 1,
        @Query("count") count: Int = 200,
    ): Response<ClientsResponse>

    /** Сотрудники филиала (`/book_staff`) — для карточек в приложении. */
    @GET("v1/yclients/staff")
    suspend fun getBookStaff(): Response<StaffResponse>

    /**
     * Начисленная ЗП по каждому дню периода (API-HOWTO 5.1).
     *
     * Ограничения YClients проходят насквозь: будущее не считается, диапазон в
     * будущее отбивается целиком (422), у сотрудника без прав владельца — 403.
     */
    @GET("v1/yclients/salary/daily")
    suspend fun getSalaryDaily(
        @Query("date_from") dateFrom: String,
        @Query("date_to") dateTo: String,
    ): Response<JsonElement>

    /**
     * Список закрытых начислений за период (месяц → id, сумма).
     * Период больше года отбивается с 422.
     */
    @GET("v1/yclients/salary/calculations")
    suspend fun getSalaryCalculations(
        @Query("date_from") dateFrom: String,
        @Query("date_to") dateTo: String,
    ): Response<JsonElement>

    /**
     * Детализация начисления: каждая позиция со своей ставкой (API-HOWTO 5.2).
     * Единственный источник ставок по видам работ.
     */
    @GET("v1/yclients/salary/calculations/{calculation_id}")
    suspend fun getSalaryCalculationDetails(
        @Path("calculation_id") calculationId: Long,
    ): Response<JsonElement>
}
