package ru.greemlab.neiro.data.network

import com.google.gson.annotations.SerializedName

/**
 * Ответ со списком записей (расписание).
 */
data class RecordsResponse(
    val success: Boolean,
    val data: List<RecordData>?,
    val meta: RecordsMeta?,
)

data class RecordsMeta(
    @SerializedName("total_count") val totalCount: Int?,
)

/**
 * Данные одной записи (занятия).
 */
data class RecordData(
    val id: Long,
    @SerializedName("company_id") val companyId: Int,
    @SerializedName("staff_id") val staffId: Int,
    // Nullable: Gson может оставить null в non-null поле; валидация — в репозитории.
    val date: String?,
    val datetime: String?,
    @SerializedName("create_date") val createDate: String?,
    val comment: String?,
    val attendance: Int,
    @SerializedName("seance_length") val seanceLength: Int?,
    val length: Int?,
    @SerializedName("visit_attendance") val visitAttendance: Int?,
    val deleted: Boolean? = null,
    @SerializedName("last_change_date") val lastChangeDate: String? = null,
    /** Групповое событие (интенсив и прочие группы). У обычной записи `null`. */
    @SerializedName("activity_id") val activityId: Long? = null,
    val client: ClientData?,
    val services: List<ServiceData>?,
)

/**
 * Данные клиента (ученика).
 */
data class ClientData(
    val id: Long,
    val name: String?,
    val surname: String?,
    val patronymic: String?,
    @SerializedName("display_name") val displayName: String?,
    val phone: String?,
    val email: String?,
    @SerializedName("success_visits_count") val successVisitsCount: Int?,
    @SerializedName("fail_visits_count") val failVisitsCount: Int?,
)

/**
 * Данные услуги.
 */
data class ServiceData(
    val id: Long,
    val title: String?,
    /** Сколько клиент заплатил деньгами: `0`, если списано с абонемента. */
    val cost: Double?,
    @SerializedName("cost_to_pay") val costToPay: Double?,
    /** Базовая цена на момент записи — в отличие от [cost] не зависит от абонемента. */
    @SerializedName("first_cost") val firstCost: Double?,
    @SerializedName("cost_per_unit") val costPerUnit: Double?,
    val discount: Double?,
    val amount: Int?,
)

/**
 * Ответ со списком клиентов.
 */
data class ClientsResponse(
    val success: Boolean,
    val data: List<ClientData>?,
    val meta: ClientsMeta?,
)

/**
 * Ответ публичного эндпоинта /book_staff — список сотрудников филиала.
 * Не требует user_token, только partner_token.
 */
data class StaffResponse(
    val success: Boolean,
    val data: List<StaffData>?,
)

/**
 * Сотрудник салона (мастер/педагог/нейропсихолог и т.п.).
 */
data class StaffData(
    val id: Int,
    val name: String?,
    val specialization: String?,
    val bookable: Boolean?,
    val fired: Int?,
)

data class ClientsMeta(
    @SerializedName("total_count") val totalCount: Int?,
)

/**
 * Ответ об ошибке API.
 */
data class ApiError(
    val success: Boolean,
    val meta: ApiErrorMeta?,
)

data class ApiErrorMeta(
    val message: String?,
)
