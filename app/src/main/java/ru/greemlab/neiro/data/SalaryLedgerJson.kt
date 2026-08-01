package ru.greemlab.neiro.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Сериализация истории ЗП. Как и [UserProfileJson], наружу исключений не бросает:
 * битые данные означают пустую историю, а не краш на старте.
 */
object SalaryLedgerJson {
    private val gson = Gson()
    private val type = object : TypeToken<SalaryLedger>() {}.type

    fun toJson(ledger: SalaryLedger): String = gson.toJson(ledger)

    /** Не бросает исключений: битые данные = пустая история, как в [UserProfileJson]. */
    fun fromJson(json: String?): SalaryLedger {
        if (json.isNullOrBlank() || json == "{}") return SalaryLedger.Empty
        return runCatching { gson.fromJson<SalaryLedger>(json, type) }
            .getOrNull() ?: SalaryLedger.Empty
    }

    /**
     * История из бэкапа, годная к применению, или `null`.
     *
     * Отсутствие ключа и битый JSON означают «не трогать историю», а не
     * «очистить»: ручные правки — самая невосстановимая часть данных
     * (FOUNDATION 8.4).
     */
    fun restorable(json: String?): SalaryLedger? =
        fromJson(json).takeIf { it != SalaryLedger.Empty }
}
