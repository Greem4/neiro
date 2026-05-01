package ru.greemlab.neiro.ui.profile

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.ui.calendar.CalendarViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.*

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileContent(
    profileViewModel: ProfileViewModel,
    calendarViewModel: CalendarViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val profile by profileViewModel.userProfile.collectAsState()
    val dayData by calendarViewModel.dayData.collectAsState()
    val scrollState = rememberScrollState()

    // Расчет статистики
    val today = LocalDate.now()
    
    // 1. Занятий проведено (записи до сегодня включительно)
    val pastSessionsCount = dayData.filter { it.key <= today }.values.sumOf { it.size }
    
    // 2. Сколько осталось (записи после сегодня)
    val futureSessionsCount = dayData.filter { it.key > today }.values.sumOf { it.size }
    
    // 3. Заработано всего
    val totalEarned = pastSessionsCount * profile.pricePerSession
    
    // 4. Заработано с учетом налога
    val earnedWithTax = (totalEarned - profile.monthlyTaxAmount).coerceAtLeast(0.0)
    
    // 5. Ожидаемый заработок (все записи * цена - налог)
    val totalSessions = pastSessionsCount + futureSessionsCount
    val totalExpectedGross = totalSessions * profile.pricePerSession
    val expectedEarnings = (totalExpectedGross - profile.monthlyTaxAmount).coerceAtLeast(0.0)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        // Заголовок профиля
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = profile.name.ifBlank { "Пользователь" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = profile.activityType.ifBlank { "Профессия не указана" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Блок Статистики
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Статистика работы", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                
                StatRow("Занятий проведено", pastSessionsCount.toString())
                StatRow("Осталось занятий", futureSessionsCount.toString())
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                StatRow("Заработано всего", "${totalEarned.toInt()} ₽")
                StatRow("Чистыми (с налогом)", "${earnedWithTax.toInt()} ₽", isHighlight = true)
                StatRow("Ожидаемый доход", "${expectedEarnings.toInt()} ₽")
            }
        }

        // Кнопка настроек
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(12.dp)
        ) {
            Icon(Icons.Default.Settings, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Настройки профиля")
        }
    }
}

@Composable
fun StatRow(label: String, value: String, isHighlight: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value, 
            style = MaterialTheme.typography.bodyMedium, 
            fontWeight = FontWeight.Bold,
            color = if (isHighlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DayChip(
    day: DayOfWeek,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    
    val shortName = day.getDisplayName(TextStyle.SHORT, Locale("ru")).take(2)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable { onToggle() }
    ) {
        Text(
            text = shortName,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}
