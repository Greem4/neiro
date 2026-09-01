package ru.greemlab.neiro.ui.settings

import android.content.Context

/**
 * Какие строки прибыли показывать в календаре и в диалоге «Финансы».
 *
 * Значения по умолчанию: показывается всё, кроме налога за месяц и стоимости
 * одного занятия. Дефолт достаётся только тем, кто настройки ни разу не
 * трогал: [ProfitDisplayPreferences.save] пишет ключи в prefs, а прочитанное
 * значение всегда сильнее умолчания.
 */
data class ProfitDisplaySettings(
    val showNetProfit: Boolean = true,
    val showGrossEarned: Boolean = true,
    val showTax: Boolean = false,
    val showExpectedIncome: Boolean = true,
    val showPricePerSession: Boolean = false,
    val showExpectedInOverview: Boolean = true,
    val showIntensiveEarnings: Boolean = true,
    val showDiagnosticsEarnings: Boolean = true,
    val showTotalProfit: Boolean = true,
    val expectedIncludesNet: Boolean = true,
    /**
     * Показывать отметку о расхождении с YClients. Прячет только показ:
     * сверка и запись в `note` продолжаются, иначе через полгода не останется
     * следов (FOUNDATION 5).
     */
    val showDiscrepancy: Boolean = true,
    /**
     * Показывать строку «Потеряно на отменах» в диалогах дня.
     *
     * Выключено по умолчанию: сумма за отменённые занятия никогда не придёт, а
     * висящий минус в дне читается как долг (решение пользователя 01.09.2026).
     * Считается она в любом случае — «Можно было заработать» её включает.
     */
    val showCancelledLoss: Boolean = false,
)

class ProfitDisplayPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Умолчания берём у самой модели, а не повторяем литералами: разъехавшись,
    // такие пары дают настройку, которая на экране показана одной, а работает
    // другой.
    private val defaults = ProfitDisplaySettings()

    init {
        turnOffLegacyOptIns()
    }

    /**
     * Одноразово гасит «Налог за месяц» и «Стоимость одного занятия».
     *
     * `showTax` когда-то был включён по умолчанию, а [save] пишет все ключи
     * разом — стоило один раз тронуть любой тумблер, и `true` уезжало в prefs
     * навсегда. Смена значения по умолчанию такие установки уже не чинит, и
     * строка продолжала появляться в диалоге «Финансы» сама по себе.
     *
     * Обе строки — служебные, для проверки расчётов, поэтому гасим их, а не
     * пытаемся угадать, где значение осознанное. Флаг не даёт миграции
     * повториться: включённое вручную после неё остаётся включённым.
     */
    private fun turnOffLegacyOptIns() {
        if (prefs.getBoolean(KEY_LEGACY_OPT_INS_CLEARED, false)) return
        prefs.edit()
            .putBoolean(KEY_SHOW_TAX, false)
            .putBoolean(KEY_SHOW_SESSION_PRICE, false)
            .putBoolean(KEY_LEGACY_OPT_INS_CLEARED, true)
            .apply()
    }

    fun read(): ProfitDisplaySettings = ProfitDisplaySettings(
        showNetProfit = prefs.getBoolean(KEY_SHOW_NET, defaults.showNetProfit),
        showGrossEarned = prefs.getBoolean(KEY_SHOW_GROSS, defaults.showGrossEarned),
        showTax = prefs.getBoolean(KEY_SHOW_TAX, defaults.showTax),
        showExpectedIncome = prefs.getBoolean(KEY_SHOW_EXPECTED, defaults.showExpectedIncome),
        showPricePerSession = prefs.getBoolean(KEY_SHOW_SESSION_PRICE, defaults.showPricePerSession),
        showExpectedInOverview = prefs.getBoolean(KEY_SHOW_EXPECTED_OVERVIEW, defaults.showExpectedInOverview),
        showIntensiveEarnings = prefs.getBoolean(KEY_SHOW_INTENSIVE, defaults.showIntensiveEarnings),
        showDiagnosticsEarnings = prefs.getBoolean(KEY_SHOW_DIAGNOSTICS, defaults.showDiagnosticsEarnings),
        showTotalProfit = prefs.getBoolean(KEY_SHOW_TOTAL, defaults.showTotalProfit),
        expectedIncludesNet = prefs.getBoolean(KEY_EXPECTED_INCLUDES_NET, defaults.expectedIncludesNet),
        showDiscrepancy = prefs.getBoolean(KEY_SHOW_DISCREPANCY, defaults.showDiscrepancy),
        showCancelledLoss = prefs.getBoolean(KEY_SHOW_CANCELLED_LOSS, defaults.showCancelledLoss),
    )

    fun save(settings: ProfitDisplaySettings) {
        prefs.edit()
            .putBoolean(KEY_SHOW_NET, settings.showNetProfit)
            .putBoolean(KEY_SHOW_GROSS, settings.showGrossEarned)
            .putBoolean(KEY_SHOW_TAX, settings.showTax)
            .putBoolean(KEY_SHOW_EXPECTED, settings.showExpectedIncome)
            .putBoolean(KEY_SHOW_SESSION_PRICE, settings.showPricePerSession)
            .putBoolean(KEY_SHOW_EXPECTED_OVERVIEW, settings.showExpectedInOverview)
            .putBoolean(KEY_SHOW_INTENSIVE, settings.showIntensiveEarnings)
            .putBoolean(KEY_SHOW_DIAGNOSTICS, settings.showDiagnosticsEarnings)
            .putBoolean(KEY_SHOW_TOTAL, settings.showTotalProfit)
            .putBoolean(KEY_EXPECTED_INCLUDES_NET, settings.expectedIncludesNet)
            .putBoolean(KEY_SHOW_DISCREPANCY, settings.showDiscrepancy)
            .putBoolean(KEY_SHOW_CANCELLED_LOSS, settings.showCancelledLoss)
            .apply()
    }

    fun update(transform: (ProfitDisplaySettings) -> ProfitDisplaySettings) {
        save(transform(read()))
    }

    companion object {
        private const val PREFS_NAME = "neiro_profit_display_prefs"
        private const val KEY_SHOW_NET = "show_net_profit"
        private const val KEY_SHOW_GROSS = "show_gross_earned"
        private const val KEY_SHOW_TAX = "show_tax"
        private const val KEY_SHOW_EXPECTED = "show_expected_income"
        private const val KEY_SHOW_SESSION_PRICE = "show_price_per_session"
        private const val KEY_SHOW_EXPECTED_OVERVIEW = "show_expected_in_overview"
        private const val KEY_SHOW_INTENSIVE = "show_intensive"
        private const val KEY_SHOW_DIAGNOSTICS = "show_diagnostics"
        private const val KEY_SHOW_TOTAL = "show_total_profit"
        private const val KEY_EXPECTED_INCLUDES_NET = "expected_includes_net"
        private const val KEY_SHOW_DISCREPANCY = "show_discrepancy"
        // Ключ новый: у тех, кто уже трогал настройки, его в prefs нет, и
        // строка гаснет сама — отдельная миграция не нужна.
        private const val KEY_SHOW_CANCELLED_LOSS = "show_cancelled_loss"
        private const val KEY_LEGACY_OPT_INS_CLEARED = "legacy_opt_ins_cleared"

        @Volatile
        private var instance: ProfitDisplayPreferences? = null

        fun get(context: Context): ProfitDisplayPreferences =
            instance ?: synchronized(this) {
                instance ?: ProfitDisplayPreferences(context).also { instance = it }
            }
    }
}
