package ru.greemlab.neiro.data.network

import com.google.gson.annotations.SerializedName

/**
 * Запрос авторизации в YClients API.
 */
data class AuthRequest(
    val login: String,
    val password: String,
)

/**
 * Ответ авторизации.
 */
data class AuthResponse(
    val success: Boolean,
    val data: AuthData?,
    val meta: List<Any>?,
)

data class AuthData(
    @SerializedName("id") val userId: Int,
    @SerializedName("user_token") val userToken: String,
    val name: String?,
    val phone: String?,
    val login: String?,
    val email: String?,
    val avatar: String?,
)

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
    val date: String,
    val datetime: String?,
    @SerializedName("create_date") val createDate: String?,
    val comment: String?,
    val attendance: Int,
    @SerializedName("seance_length") val seanceLength: Int?,
    val length: Int?,
    @SerializedName("visit_attendance") val visitAttendance: Int?,
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
    val cost: Double?,
    @SerializedName("cost_to_pay") val costToPay: Double?,
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
