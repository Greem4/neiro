package ru.greemlab.neiro.data.network

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
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

private val salaryGson = Gson()

/**
 * Конверт зарплатного ответа, разобранный вручную.
 *
 * Типизированной модели у посуточного расчёта больше нет намеренно. Живой ответ
 * не совпал с документацией дважды подряд: `data` оказался объектом, а не
 * массивом, следом `meta` — массивом, а не объектом. Каждый раз Gson падал на
 * конверте целиком, и зарплата не доезжала вообще. Здесь ни одно поле не может
 * уронить разбор: что не поняли — то пусто.
 */
internal class SalaryEnvelope(private val root: JsonElement?) {

    private val obj: JsonObject? = root?.takeIf { it.isJsonObject }?.asJsonObject

    /** `false` только если API прямо это сказал: отсутствие поля отказом не считаем. */
    val isSuccess: Boolean get() = obj?.get("success").asBooleanOrNull() != false

    val data: JsonElement? get() = obj?.get("data")

    /** Текст отказа: на 422 YClients кладёт сюда объяснение (API-HOWTO 1.3). */
    val message: String?
        get() {
            // `meta` бывает и объектом с текстом, и пустым массивом — приводим
            // к объекту, иначе `get` брать не у чего.
            val meta = obj?.get("meta")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val text = meta.get("message")?.takeIf { it.isJsonPrimitive } ?: return null
            return text.asString.takeIf { it.isNotBlank() }
        }
}

/** `true`, `"true"`, `1` — всё это «да»; что угодно другое — `null`. */
private fun JsonElement?.asBooleanOrNull(): Boolean? {
    val primitive = this?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    return when {
        primitive.isBoolean -> primitive.asBoolean
        primitive.isNumber -> primitive.asInt != 0
        primitive.isString -> primitive.asString.equals("true", ignoreCase = true) ||
            primitive.asString == "1"

        else -> null
    }
}

/**
 * Достаёт посуточные позиции из `data` любой из правдоподобных форм.
 *
 * Живой ответ никто не видел, а сломаться на форме нельзя: до этого разбора
 * зарплата не доезжала вообще, и вся история считалась по цене профиля.
 * Понимаем три варианта:
 *
 *  - массив позиций — как в документации;
 *  - объект `{"2025-01-16": {...}}` — ключ и есть дата;
 *  - объект-обёртка, внутри которого лежит массив позиций.
 *
 * Всё, что не разобралось, молча пропускается: одна незнакомая позиция не
 * должна уносить с собой остальной месяц.
 */
internal fun salaryDailyItems(data: JsonElement?): List<SalaryDailyItem> {
    if (data == null || data.isJsonNull) return emptyList()
    if (data.isJsonArray) return data.asJsonArray.mapNotNull { it.toDailyItem(key = null) }
    if (!data.isJsonObject) return emptyList()

    val obj = data.asJsonObject

    // Карта «дата → расчёт»: ключ становится датой, если внутри её нет.
    val byKey = obj.entrySet().mapNotNull { (key, value) -> value.toDailyItem(key = key) }
    if (byKey.isNotEmpty()) return byKey

    // Обёртка: где-то внутри массив позиций.
    val nested = obj.entrySet().firstOrNull { it.value.isJsonArray }?.value
    return nested?.asJsonArray?.mapNotNull { it.toDailyItem(key = null) }.orEmpty()
}

/**
 * Одна позиция: либо `{date, period_calculation}`, либо сам расчёт, у которого
 * дата вынесена в ключ объекта.
 */
private fun JsonElement.toDailyItem(key: String?): SalaryDailyItem? {
    if (!isJsonObject) return null

    val asItem = runCatching {
        salaryGson.fromJson(this, SalaryDailyItem::class.java)
    }.getOrNull()
    val date = asItem?.date ?: key ?: return null
    asItem?.calculation?.let { return SalaryDailyItem(date = date, calculation = it) }

    // Вложенного period_calculation нет — значит расчёт лежит здесь же.
    val asCalculation = runCatching {
        salaryGson.fromJson(this, SalaryPeriodCalculation::class.java)
    }.getOrNull() ?: return null
    if (asCalculation.salary == null && asCalculation.servicesCount == null) return null
    return SalaryDailyItem(date = date, calculation = asCalculation)
}

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

/**
 * Одно начисление в списке (живой ответ, фирма 520135).
 *
 * Сумма лежит в `amount` — **не** в `sum`, как предполагала первая модель, и
 * приходит числом. `status` — тот самый признак «начисление закрыто», который
 * считался непроверенным: у всех проведённых месяцев там `confirmed`.
 */
data class SalaryCalculationSummary(
    val id: Long?,
    @SerializedName("date_from") val dateFrom: String?,
    @SerializedName("date_to") val dateTo: String?,
    val amount: String?,
    val status: String?,
) {
    val isConfirmed: Boolean get() = status.equals("confirmed", ignoreCase = true)
}

/** Список начислений: `data` — массив. */
internal fun salaryCalculations(data: JsonElement?): List<SalaryCalculationSummary> =
    data?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { element ->
        runCatching {
            salaryGson.fromJson(element, SalaryCalculationSummary::class.java)
        }.getOrNull()?.takeIf { it.id != null }
    }.orEmpty()

/**
 * Позиции начисления: `data` — **объект**, а позиции внутри, в `salary_items`.
 *
 * Модель ждала здесь массив, и разбор падал молча — из-за этого автоподстановка
 * ставок из API не работала ни разу. Массив тоже принимаем: вдруг у другого
 * начисления форма окажется прежней.
 */
internal fun salaryCalculationItems(data: JsonElement?): List<SalaryCalculationItem> {
    val array = when {
        data == null -> null
        data.isJsonArray -> data.asJsonArray
        data.isJsonObject -> data.asJsonObject.get("salary_items")
            ?.takeIf { it.isJsonArray }?.asJsonArray
            ?: data.asJsonObject.entrySet().firstOrNull { it.value.isJsonArray }
                ?.value?.asJsonArray

        else -> null
    } ?: return emptyList()

    return array.mapNotNull { element ->
        runCatching { salaryGson.fromJson(element, SalaryCalculationItem::class.java) }.getOrNull()
    }
}

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
