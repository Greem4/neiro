package ru.greemlab.neiro.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.greemlab.neiro.R
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.theme.neiroSemanticColors
import ru.greemlab.neiro.ui.components.DayChip
import ru.greemlab.neiro.ui.settings.SettingsGroupCard
import ru.greemlab.neiro.ui.settings.SettingsSwitchRow
import ru.greemlab.neiro.ui.sync.SyncViewModel
import java.time.DayOfWeek

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ProfileViewModel,
    syncViewModel: SyncViewModel,
    onBack: () -> Unit,
    onOpenYClientsAuth: () -> Unit,
) {
    val profile by viewModel.userProfile.collectAsStateWithLifecycle()
    val isLoggedInToYClients by syncViewModel.isLoggedIn.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    SettingsScreenImpl(
        profile = profile,
        yclientsLoggedIn = isLoggedInToYClients,
        yclientsUserName = syncViewModel.yclientsUserName,
        onNameChange = viewModel::updateName,
        onActivityChange = viewModel::updateActivityType,
        onPriceChange = viewModel::updatePrice,
        onDiagnosticsPriceChange = viewModel::updateDiagnosticsPrice,
        onIntensiveChildPriceChange = viewModel::updateIntensiveChildPrice,
        onTaxChange = viewModel::updateTaxAmount,
        onSalaryAdvanceChange = viewModel::updateSalaryAdvanceOnCard,
        onSalaryMainChange = viewModel::updateSalaryMainOnCard,
        onToggleDay = viewModel::toggleWorkingDay,
        onShowAvatarChange = viewModel::updateShowAvatar,
        onOpenYClientsAuth = onOpenYClientsAuth,
        onLogoutYClients = syncViewModel::logoutYClients,
        onChangeAccount = {
            scope.launch {
                syncViewModel.logoutYClientsAwait()
                onOpenYClientsAuth()
            }
        },
        onBack = {
            // То же условие, что и enabled у кнопки «Начать работу» ниже —
            // иначе TopBar-стрелка «Назад» завершала регистрацию по одному
            // только имени, без вида деятельности (P6).
            if (!profile.isRegistered && profile.name.isNotBlank() && profile.activityType.isNotBlank()) {
                viewModel.completeRegistration()
            }
            onBack()
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenImpl(
    profile: UserProfile,
    yclientsLoggedIn: Boolean,
    yclientsUserName: String?,
    onNameChange: (String) -> Unit,
    onActivityChange: (String) -> Unit,
    onPriceChange: (Double) -> Unit,
    onDiagnosticsPriceChange: (Double) -> Unit,
    onIntensiveChildPriceChange: (Double) -> Unit,
    onTaxChange: (Double) -> Unit,
    onSalaryAdvanceChange: (Double) -> Unit,
    onSalaryMainChange: (Double) -> Unit,
    onToggleDay: (DayOfWeek) -> Unit,
    onShowAvatarChange: (Boolean) -> Unit,
    onOpenYClientsAuth: () -> Unit,
    onLogoutYClients: () -> Unit,
    onChangeAccount: () -> Unit,
    onBack: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val isNewUser = !profile.isRegistered

    // Локальные текстовые состояния: инициализируем из профиля, синхронизируем
    // при внешнем изменении (например, после импорта данных).
    var nameText by remember(profile.name) { mutableStateOf(profile.name) }
    var activityText by remember(profile.activityType) { mutableStateOf(profile.activityType) }
    var priceText by remember(profile.pricePerSession) {
        mutableStateOf(formatMoneyForInput(profile.pricePerSession))
    }
    var diagnosticsPriceText by remember(profile.pricePerDiagnostics) {
        mutableStateOf(formatMoneyForInput(profile.pricePerDiagnostics))
    }
    var intensiveChildPriceText by remember(profile.pricePerIntensiveChild) {
        mutableStateOf(formatMoneyForInput(profile.pricePerIntensiveChild))
    }
    var taxText by remember(profile.monthlyTaxAmount) {
        mutableStateOf(formatMoneyForInput(profile.monthlyTaxAmount))
    }
    var salaryAdvanceText by remember(profile.salaryAdvanceOnCard) {
        mutableStateOf(formatMoneyForInput(profile.salaryAdvanceOnCard))
    }
    var salaryMainText by remember(profile.salaryMainOnCard) {
        mutableStateOf(formatMoneyForInput(profile.salaryMainOnCard))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки профиля") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState),
        ) {
            OutlinedTextField(
                value = nameText,
                onValueChange = {
                    nameText = it
                    onNameChange(it)
                },
                label = { Text("Имя") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = activityText,
                onValueChange = {
                    activityText = it
                    onActivityChange(it)
                },
                label = { Text("Вид деятельности") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Work, contentDescription = null) },
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = priceText,
                onValueChange = { value ->
                    val sanitized = sanitizeMoneyInput(value)
                    priceText = sanitized
                    onPriceChange(sanitized.toDoubleOrNull() ?: 0.0)
                },
                label = { Text("Ставка сотрудника за занятие (₽)") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.AttachMoney, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = diagnosticsPriceText,
                onValueChange = { value ->
                    val sanitized = sanitizeMoneyInput(value)
                    diagnosticsPriceText = sanitized
                    onDiagnosticsPriceChange(sanitized.toDoubleOrNull() ?: 0.0)
                },
                label = { Text("Цена за диагностику (₽)") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.AttachMoney, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = intensiveChildPriceText,
                onValueChange = { value ->
                    val sanitized = sanitizeMoneyInput(value)
                    intensiveChildPriceText = sanitized
                    onIntensiveChildPriceChange(sanitized.toDoubleOrNull() ?: 0.0)
                },
                label = { Text("Ставка за ребёнка в интенсиве (₽)") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.AttachMoney, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = taxText,
                onValueChange = { value ->
                    val sanitized = sanitizeMoneyInput(value)
                    taxText = sanitized
                    onTaxChange(sanitized.toDoubleOrNull() ?: 0.0)
                },
                label = { Text("Налог в месяц (₽)") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Receipt, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = salaryAdvanceText,
                onValueChange = { value ->
                    val sanitized = sanitizeMoneyInput(value)
                    salaryAdvanceText = sanitized
                    onSalaryAdvanceChange(sanitized.toDoubleOrNull() ?: 0.0)
                },
                label = { Text("Аванс на карту (₽), ~") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.AttachMoney, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = salaryMainText,
                onValueChange = { value ->
                    val sanitized = sanitizeMoneyInput(value)
                    salaryMainText = sanitized
                    onSalaryMainChange(sanitized.toDoubleOrNull() ?: 0.0)
                },
                label = { Text("Зарплата на карту (₽), ~") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.AttachMoney, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Рабочие дни",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                for (day in DayOfWeek.entries) {
                    DayChip(
                        day = day,
                        isSelected = profile.workingDays.contains(day),
                        onToggle = { onToggleDay(day) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            SettingsGroupCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_profile_show_avatar),
                    subtitle = stringResource(R.string.settings_profile_show_avatar_hint),
                    checked = profile.showAvatar,
                    onCheckedChange = onShowAvatarChange,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(20.dp))

            YClientsAccountSection(
                isLoggedIn = yclientsLoggedIn,
                userName = yclientsUserName,
                onOpenYClientsAuth = onOpenYClientsAuth,
                onLogout = onLogoutYClients,
                onChangeAccount = onChangeAccount,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = nameText.isNotBlank() && activityText.isNotBlank(),
            ) {
                Text(if (isNewUser) "Начать работу" else "Сохранить и выйти")
            }
        }
    }
}

/**
 * Карточка управления аккаунтом YClients внутри экрана настроек профиля.
 *
 * Когда пользователь авторизован — показываем имя из ответа `/auth` и две
 * кнопки: сменить аккаунт (откроет форму входа) и выйти. Если не авторизован —
 * выводим компактное приглашение войти, отсылающее на ту же форму, что и
 * жёлтая кнопка в боковой панели профиля.
 */
@Composable
private fun YClientsAccountSection(
    isLoggedIn: Boolean,
    userName: String?,
    onOpenYClientsAuth: () -> Unit,
    onLogout: () -> Unit,
    onChangeAccount: () -> Unit,
) {
    Text(
        text = "Аккаунт YClients",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 12.dp),
    )

    if (isLoggedIn) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = userName?.takeIf { it.isNotBlank() } ?: "Пользователь YClients",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = neiroSemanticColors.scheduleHeader,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Подключено",
                                style = MaterialTheme.typography.bodySmall,
                                color = neiroSemanticColors.scheduleHeader,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    // «Сменить аккаунт» = выйти и сразу открыть форму входа, чтобы
                    // пользователь не возвращался к карточке «Вы авторизованы».
                    // onChangeAccount ждёт реального завершения logout — иначе
                    // AuthScreen на миг показывает LoggedInContent вместо формы.
                    onClick = onChangeAccount,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Сменить аккаунт")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Выйти из YClients",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "YClients не подключён",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Подключите аккаунт, чтобы автоматически подтягивать записи в календарь.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onOpenYClientsAuth,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Войти в YClients")
                }
            }
        }
    }
}

private fun sanitizeMoneyInput(raw: String): String =
    raw.filter { it.isDigit() }.trimStart('0').ifEmpty { if (raw.startsWith('0')) "0" else "" }

private fun formatMoneyForInput(value: Double): String =
    if (value > 0.0) value.toLong().toString() else ""

@Preview(showBackground = true, name = "Settings Light")
@Composable
private fun SettingsScreenLightPreview() {
    NeiroTheme(darkTheme = false) {
        SettingsScreenImpl(
            profile = UserProfile(
                name = "Иван Иванов",
                activityType = "Репетитор",
                pricePerSession = 1500.0,
                monthlyTaxAmount = 5000.0,
                workingDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            ),
            yclientsLoggedIn = true,
            yclientsUserName = "Светлана Зеленкина",
            onNameChange = {},
            onActivityChange = {},
            onPriceChange = {},
            onDiagnosticsPriceChange = {},
            onIntensiveChildPriceChange = {},
            onTaxChange = {},
            onSalaryAdvanceChange = {},
            onSalaryMainChange = {},
            onToggleDay = {},
            onShowAvatarChange = {},
            onOpenYClientsAuth = {},
            onLogoutYClients = {},
            onChangeAccount = {},
            onBack = {},
        )
    }
}
