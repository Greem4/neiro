package ru.greemlab.neiro.ui.profile

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.components.DayChip
import java.time.DayOfWeek

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ProfileViewModel,
    onBack: () -> Unit
) {
    val profileState by viewModel.userProfile.collectAsState()
    val profile = profileState ?: UserProfile()
    
    SettingsScreenImpl(
        profile = profile,
        onNameChange = viewModel::updateName,
        onActivityChange = viewModel::updateActivityType,
        onPriceChange = viewModel::updatePrice,
        onTaxChange = viewModel::updateTaxAmount,
        onToggleDay = viewModel::toggleWorkingDay,
        onBack = {
            if (!profile.isRegistered && profile.name.isNotBlank()) {
                viewModel.completeRegistration()
            }
            onBack()
        }
    )
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenImpl(
    profile: UserProfile,
    onNameChange: (String) -> Unit,
    onActivityChange: (String) -> Unit,
    onPriceChange: (Double) -> Unit,
    onTaxChange: (Double) -> Unit,
    onToggleDay: (DayOfWeek) -> Unit,
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()
    val isNewUser = !profile.isRegistered

    var nameText by remember { mutableStateOf("") }
    var activityText by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var taxText by remember { mutableStateOf("") }

    LaunchedEffect(profile.isRegistered) {
        if (nameText.isEmpty()) nameText = profile.name
        if (activityText.isEmpty()) activityText = profile.activityType
        if (priceText.isEmpty() && profile.pricePerSession != 0.0) priceText = profile.pricePerSession.toString().removeSuffix(".0")
        if (taxText.isEmpty() && profile.monthlyTaxAmount != 0.0) taxText = profile.monthlyTaxAmount.toString().removeSuffix(".0")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки профиля") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            // Имя
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
                singleLine = true
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
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = priceText,
                onValueChange = { 
                    priceText = it
                    onPriceChange(it.toDoubleOrNull() ?: 0.0)
                },
                label = { Text("Цена за занятие (₽)") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.AttachMoney, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = taxText,
                onValueChange = { 
                    taxText = it
                    onTaxChange(it.toDoubleOrNull() ?: 0.0)
                },
                label = { Text("Налог в месяц (₽)") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.AttachMoney, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Рабочие дни",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DayOfWeek.values().forEach { day ->
                    DayChip(
                        day = day,
                        isSelected = profile.workingDays.contains(day),
                        onToggle = { onToggleDay(day) }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = nameText.isNotBlank() && activityText.isNotBlank()
            ) {
                Text(if (isNewUser) "Начать работу" else "Сохранить и выйти")
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Preview(showBackground = true, name = "Settings Light")
@Composable
fun SettingsScreenLightPreview() {
    NeiroTheme(darkTheme = false) {
        SettingsScreenImpl(
            profile = UserProfile(
                name = "Иван Иванов",
                activityType = "Репетитор",
                pricePerSession = 1500.0,
                monthlyTaxAmount = 5000.0,
                workingDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
            ),
            onNameChange = {},
            onActivityChange = {},
            onPriceChange = {},
            onTaxChange = {},
            onToggleDay = {},
            onBack = {}
        )
    }
}
