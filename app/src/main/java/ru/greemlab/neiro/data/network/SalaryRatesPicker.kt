package ru.greemlab.neiro.data.network

/** Позиция обычной записи. Всё прочее (`activity`, штрафы, бонусы) пропускаем. */
private const val ITEM_TYPE_RECORD = "record"

/** Диагностику ловим по названию услуги — так же, как это делает синк. */
private const val DIAGNOSTICS_MARKER = "диагностик"

/**
 * Ставки из позиций начисления (FOUNDATION 6.2): берём `salary_sum` позиции,
 * а не `cost` — это ЗП, а не цена клиента (1400, а не 3000; диагностика 2250,
 * а не 4500).
 *
 * Берётся самое частое значение: одно случайное начисление может оказаться
 * акцией или доплатой. Незнакомый `item_type_slug` пропускается — разбор не
 * должен падать на бонусах и штрафах, которых у этого сотрудника нет,
 * но в схемах YClients они бывают.
 */
fun extractSalaryRates(items: List<SalaryCalculationItem>?): SalaryRatesFromApi {
    val sessionSums = mutableListOf<Double>()
    val diagnosticsSums = mutableListOf<Double>()

    for (item in items.orEmpty()) {
        if (item.itemTypeSlug != ITEM_TYPE_RECORD) continue
        for (target in item.targets.orEmpty()) {
            val sum = target.salarySum.toMoneyOrNull()
                ?: item.salarySum.toMoneyOrNull()
                ?: continue
            if (sum <= 0.0) continue
            if (target.title.orEmpty().contains(DIAGNOSTICS_MARKER, ignoreCase = true)) {
                diagnosticsSums += sum
            } else {
                sessionSums += sum
            }
        }
    }

    return SalaryRatesFromApi(
        pricePerSession = sessionSums.mostFrequentOrNull(),
        pricePerDiagnostics = diagnosticsSums.mostFrequentOrNull(),
    )
}

private fun List<Double>.mostFrequentOrNull(): Double? =
    groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
