package ru.greemlab.neiro.data.network

import com.google.gson.annotations.SerializedName

/**
 * Модели зарплатных ответов YClients (API-HOWTO 5.1 и 5.2).
 *
 * Все поля nullable: Gson не проверяет Kotlin-nullability, а числа YClients
 * отдаёт строками (`"salary":"12050"`). Разбор — только через [toMoneyOrNull].
 */

/** Деньги из ответа: `"12050"`, `"18 000"`, `"1 400,50"` → Double; мусор → null. */
internal fun String?.toMoneyOrNull(): Double? =
    this?.trim()
        // Разделители разрядов приходят и обычным пробелом, и неразрывным.
        ?.filterNot { it == ' ' || it == '\u00A0' }
        ?.replace(',', '.')
        ?.toDoubleOrNull()

data class SalaryDailyResponse(
    val success: Boolean?,
    val data: List<SalaryDailyItem>?,
    val meta: SalaryMeta?,
)

data class SalaryDailyItem(
    val date: String?,
    @SerializedName("period_calculation") val calculation: SalaryPeriodCalculation?,
)

data class SalaryPeriodCalculation(
    @SerializedName("working_days_count") val workingDaysCount: Double?,
    /** Индивидуальные услуги: занятия и диагностики вместе. */
    @SerializedName("services_count") val servicesCount: Int?,
    /** Групповые события (интенсивы) — считаются отдельно от [servicesCount]. */
    @SerializedName("group_services_count") val groupServicesCount: Int?,
    @SerializedName("services_sum") val servicesSum: String?,
    @SerializedName("total_sum") val totalSum: String?,
    val salary: String?,
)

data class SalaryMeta(val message: String?)

data class SalaryCalculationListResponse(
    val success: Boolean?,
    val data: List<SalaryCalculationSummary>?,
    val meta: SalaryMeta?,
)

data class SalaryCalculationSummary(
    val id: Long?,
    @SerializedName("date_from") val dateFrom: String?,
    @SerializedName("date_to") val dateTo: String?,
    val sum: String?,
)

data class SalaryCalculationDetailsResponse(
    val success: Boolean?,
    val data: List<SalaryCalculationItem>?,
    val meta: SalaryMeta?,
)

data class SalaryCalculationItem(
    val date: String?,
    val time: String?,
    /** `record` — обычная запись, `activity` — групповое событие (интенсив). */
    @SerializedName("item_type_slug") val itemTypeSlug: String?,
    @SerializedName("record_id") val recordId: Long?,
    @SerializedName("client_id") val clientId: Long?,
    val cost: String?,
    @SerializedName("salary_sum") val salarySum: String?,
    val targets: List<SalaryTarget>?,
)

data class SalaryTarget(
    @SerializedName("target_type_slug") val targetTypeSlug: String?,
    @SerializedName("target_id") val targetId: Long?,
    val title: String?,
    /** База, от которой считается процент — прайс, а не оплата клиента. */
    val cost: String?,
    @SerializedName("salary_sum") val salarySum: String?,
    @SerializedName("salary_calculation") val salaryCalculation: SalaryCalculationRule?,
)

data class SalaryCalculationRule(
    /** `fix` | `percent` | что-то ещё, чего мы пока не видели. */
    @SerializedName("type_slug") val typeSlug: String?,
    val value: Double?,
)

/** Факт за один день: сколько начислено и из скольких услуг. */
data class DayFact(
    val salary: Double,
    val servicesCount: Int,
    val groupServicesCount: Int,
)

/** Ставки, вытащенные из детализации последнего начисления (FOUNDATION 6.2). */
data class SalaryRatesFromApi(
    val pricePerSession: Double? = null,
    val pricePerDiagnostics: Double? = null,
) {
    val isEmpty: Boolean get() = pricePerSession == null && pricePerDiagnostics == null
}
