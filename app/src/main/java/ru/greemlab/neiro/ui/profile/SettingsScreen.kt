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
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ProfileViewModel,
    onBack: () -> Unit
) {
    val profile by viewModel.userProfile.collectAsState()
    val scrollState = rememberScrollState()

    // Локальные состояния для всех полей ввода
    var nameText by remember { mutableStateOf("") }
    var activityText by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var taxText by remember { mutableStateOf("") }

    // Инициализация при загрузке профиля
    LaunchedEffect(profile) {
        // Обновляем только если локальное поле пустое (первая загрузка) 
        // или если во ViewModel пришло явно другое значение не от нас
        if (nameText != profile.name && nameText.isEmpty()) nameText = profile.name
        if (activityText != profile.activityType && activityText.isEmpty()) activityText = profile.activityType
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
                    viewModel.updateName(it)
                },
                label = { Text("Имя") },
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
                label = { Text("Вид деятельности") },
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
                label = { Text("Цена за занятие (₽)") },
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
                        onToggle = { viewModel.toggleWorkingDay(day) }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Готово")
            }
        }
    }
}
