package ru.greemlab.neiro.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.greemlab.neiro.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfitDisplaySettingsScreen(
    onBack: () -> Unit,
    viewModel: AppSettingsViewModel = viewModel(),
) {
    var profitDisplay by remember { mutableStateOf(viewModel.profitDisplaySettings()) }

    fun updateProfit(transform: (ProfitDisplaySettings) -> ProfitDisplaySettings) {
        profitDisplay = transform(profitDisplay).also(viewModel::setProfitDisplaySettings)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_profit_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_profit_screen_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            SettingsSection(title = stringResource(R.string.settings_profit_section_main)) {
                SettingsGroupCard {
                    ProfitDisplaySwitch(
                        title = stringResource(R.string.settings_profit_net),
                        subtitle = stringResource(R.string.settings_profit_net_hint),
                        icon = Icons.Rounded.Payments,
                        checked = profitDisplay.showNetProfit,
                        onCheckedChange = { enabled -> updateProfit { it.copy(showNetProfit = enabled) } },
                    )
                    ProfitDisplaySwitch(
                        title = stringResource(R.string.settings_profit_gross),
                        subtitle = stringResource(R.string.settings_profit_gross_hint),
                        checked = profitDisplay.showGrossEarned,
                        onCheckedChange = { enabled -> updateProfit { it.copy(showGrossEarned = enabled) } },
                        showDivider = true,
                    )
                    ProfitDisplaySwitch(
                        title = stringResource(R.string.settings_profit_tax),
                        subtitle = stringResource(R.string.settings_profit_tax_hint),
                        checked = profitDisplay.showTax,
                        onCheckedChange = { enabled -> updateProfit { it.copy(showTax = enabled) } },
                        showDivider = true,
                    )
                    ProfitDisplaySwitch(
                        title = stringResource(R.string.settings_profit_total),
                        subtitle = stringResource(R.string.settings_profit_total_hint),
                        checked = profitDisplay.showTotalProfit,
                        onCheckedChange = { enabled -> updateProfit { it.copy(showTotalProfit = enabled) } },
                        showDivider = true,
                    )
                    ProfitDisplaySwitch(
                        title = stringResource(R.string.settings_profit_discrepancy),
                        subtitle = stringResource(R.string.settings_profit_discrepancy_hint),
                        checked = profitDisplay.showDiscrepancy,
                        onCheckedChange = { enabled -> updateProfit { it.copy(showDiscrepancy = enabled) } },
                        showDivider = true,
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.settings_profit_section_forecast)) {
                SettingsGroupCard {
                    ProfitDisplaySwitch(
                        title = stringResource(R.string.settings_profit_expected_overview),
                        subtitle = stringResource(R.string.settings_profit_expected_overview_hint),
                        checked = profitDisplay.showExpectedInOverview,
                        onCheckedChange = { enabled -> updateProfit { it.copy(showExpectedInOverview = enabled) } },
                    )
                    ProfitDisplaySwitch(
                        title = stringResource(R.string.settings_profit_expected_includes_net),
                        subtitle = stringResource(R.string.settings_profit_expected_includes_net_hint),
                        checked = profitDisplay.expectedIncludesNet,
                        onCheckedChange = { enabled -> updateProfit { it.copy(expectedIncludesNet = enabled) } },
                        showDivider = true,
                    )
                    ProfitDisplaySwitch(
                        title = stringResource(R.string.settings_profit_expected),
                        subtitle = stringResource(R.string.settings_profit_expected_hint),
                        checked = profitDisplay.showExpectedIncome,
                        onCheckedChange = { enabled -> updateProfit { it.copy(showExpectedIncome = enabled) } },
                        showDivider = true,
                    )
                    ProfitDisplaySwitch(
                        title = stringResource(R.string.settings_profit_session_price),
                        subtitle = stringResource(R.string.settings_profit_session_price_hint),
                        checked = profitDisplay.showPricePerSession,
                        onCheckedChange = { enabled -> updateProfit { it.copy(showPricePerSession = enabled) } },
                        showDivider = true,
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.settings_profit_section_extra)) {
                SettingsGroupCard {
                    ProfitDisplaySwitch(
                        title = stringResource(R.string.settings_profit_intensive),
                        checked = profitDisplay.showIntensiveEarnings,
                        onCheckedChange = { enabled -> updateProfit { it.copy(showIntensiveEarnings = enabled) } },
                    )
                    ProfitDisplaySwitch(
                        title = stringResource(R.string.settings_profit_diagnostics),
                        checked = profitDisplay.showDiagnosticsEarnings,
                        onCheckedChange = { enabled -> updateProfit { it.copy(showDiagnosticsEarnings = enabled) } },
                        showDivider = true,
                    )
                }
            }
        }
    }
}
