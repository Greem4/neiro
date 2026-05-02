package ru.greemlab.neiro.ui.registration

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.ui.profile.DayChip
import ru.greemlab.neiro.ui.profile.ProfileViewModel
import java.time.DayOfWeek

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun RegistrationScreen(
    viewModel: ProfileViewModel,
    onRegistrationComplete: () -> Unit
) {
    val profileState by viewModel.userProfile.collectAsState()
    val profile = profileState ?: UserProfile()
    val scrollState = rememberScrollState()

    // Локальные состояния для всех полей ввода, чтобы избежать прыжков курсора
    var nameText by remember { mutableStateOf("") }
    var activityText by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var taxText by remember { mutableStateOf("") }

    // Инициализация при первом запуске (если данные уже есть)
    LaunchedEffect(profile.isRegistered) {
        if (nameText.isEmpty()) nameText = profile.name
        if (activityText.isEmpty()) activityText = profile.activityType
        if (priceText.isEmpty() && profile.pricePerSession != 0.0) priceText = profile.pricePerSession.toString().removeSuffix(".0")
        if (taxText.isEmpty() && profile.monthlyTaxAmount != 0.0) taxText = profile.monthlyTaxAmount.toString().removeSuffix(".0")
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = "Добро пожаловать!",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 40.dp)
            )
            Text(
                text = "Давайте настроим ваш рабочий профиль",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Имя
            OutlinedTextField(
                value = nameText,
                onValueChange = { 
                    nameText = it
                    viewModel.updateName(it) 
                },
                label = { Text("Как вас зовут?") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Профессия
            OutlinedTextField(
                value = activityText,
                onValueChange = { 
                    activityText = it
                    viewModel.updateActivityType(it) 
                },
                label = { Text("Ваша профессия / Вид деятельности") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Work, contentDescription = null) },
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Цена
            OutlinedTextField(
                value = priceText,
                onValueChange = { 
                    priceText = it
                    viewModel.updatePrice(it.toDoubleOrNull() ?: 0.0)
                },
                label = { Text("Стоимость одного занятия (₽)") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.AttachMoney, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Налог
            OutlinedTextField(
                value = taxText,
                onValueChange = { 
                    taxText = it
                    viewModel.updateTaxAmount(it.toDoubleOrNull() ?: 0.0)
                },
                label = { Text("Налог в месяц (₽)") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.AttachMoney, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Рабочие дни
            Text(
                text = "Выберите рабочие дни недели:",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
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
                        onToggle = { viewModel.toggleWorkingDay(day) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Кнопка продолжить
            Button(
                onClick = {
                    if (profile.name.isNotBlank() && profile.activityType.isNotBlank() && profile.workingDays.isNotEmpty()) {
                        viewModel.completeRegistration()
                        onRegistrationComplete()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = profile.name.isNotBlank() && profile.activityType.isNotBlank() && profile.workingDays.isNotEmpty()
            ) {
                Text(
                    text = "Начать работу",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
