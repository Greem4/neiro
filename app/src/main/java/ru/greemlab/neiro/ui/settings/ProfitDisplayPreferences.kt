package ru.greemlab.neiro.ui.settings

import android.content.Context

/** Какие строки прибыли показывать в календаре и в диалоге «Финансы». */
data class ProfitDisplaySettings(
    val showNetProfit: Boolean = true,
    val showGrossEarned: Boolean = true,
    val showTax: Boolean = true,
    val showExpectedIncome: Boolean = true,
    val showPricePerSession: Boolean = false,
    val showExpectedInOverview: Boolean = true,
    val showIntensiveEarnings: Boolean = true,
    val showDiagnosticsEarnings: Boolean = true,
)

class ProfitDisplayPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): ProfitDisplaySettings = ProfitDisplaySettings(
        showNetProfit = prefs.getBoolean(KEY_SHOW_NET, true),
        showGrossEarned = prefs.getBoolean(KEY_SHOW_GROSS, true),
        showTax = prefs.getBoolean(KEY_SHOW_TAX, true),
        showExpectedIncome = prefs.getBoolean(KEY_SHOW_EXPECTED, true),
        showPricePerSession = prefs.getBoolean(KEY_SHOW_SESSION_PRICE, false),
        showExpectedInOverview = prefs.getBoolean(KEY_SHOW_EXPECTED_OVERVIEW, true),
        showIntensiveEarnings = prefs.getBoolean(KEY_SHOW_INTENSIVE, true),
        showDiagnosticsEarnings = prefs.getBoolean(KEY_SHOW_DIAGNOSTICS, true),
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

        @Volatile
        private var instance: ProfitDisplayPreferences? = null

        fun get(context: Context): ProfitDisplayPreferences =
            instance ?: synchronized(this) {
                instance ?: ProfitDisplayPreferences(context).also { instance = it }
            }
    }
}
