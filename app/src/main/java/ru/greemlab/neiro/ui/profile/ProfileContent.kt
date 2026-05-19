package ru.greemlab.neiro.ui.profile

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.greemlab.neiro.domain.models.UserProfile
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.calendar.CalendarViewModel
import ru.greemlab.neiro.ui.calendar.Session
import ru.greemlab.neiro.ui.calendar.SessionParser
import ru.greemlab.neiro.ui.components.NeiroLogo
import ru.greemlab.neiro.ui.components.StatRow
import java.time.LocalDate

/**
 * Содержимое боковой панели профиля в [ModalNavigationDrawer][androidx.compose.material3.ModalNavigationDrawer].
 *
 * Показывает сводную статистику, кнопки перехода в настройки профиля и приложения.
 * Отображается на [CalendarScreen]; открытие/закрытие панели — жестами drawer
 * или тапом по «N» в шапке (см. `docs/profile-drawer.md`).
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun ProfileContent(
    profileViewModel: ProfileViewModel,
    calendarViewModel: CalendarViewModel,
    onOpenSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile by profileViewModel.userProfile.collectAsState()
    val dayData by calendarViewModel.dayData.collectAsState()
    
    ProfileContentImpl(
        profile = profile,
        dayData = dayData,
        onOpenSettings = onOpenSettings,
        onOpenAppSettings = onOpenAppSettings,
        modifier = modifier
    )
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
private fun ProfileContentImpl(
    profile: UserProfile,
    dayData: Map<LocalDate, List<String>>,
    onOpenSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
    nameStyle: TextStyle = MaterialTheme.typography.headlineSmall,
    professionStyle: TextStyle = MaterialTheme.typography.bodyMedium
) {
    val scrollState = rememberScrollState()

    // Все агрегаты по dayData кэшируем — один проход вместо нескольких filter/sum.
    val stats = remember(dayData, profile.pricePerSession, profile.monthlyTaxAmount) {
        computeProfileStats(dayData, profile.pricePerSession, profile.monthlyTaxAmount)
    }
    val pastSessionsCount = stats.attendedStudents
    val futureSessionsCount = stats.totalStudents - stats.attendedStudents
    val totalEarned = stats.totalEarned
    val earnedWithTax = stats.netEarned
    val expectedEarnings = stats.netExpected

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 24.dp)
        ) {
            NeiroLogo(size = 64.dp)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name.ifBlank { "Пользователь" },
                    style = nameStyle,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = profile.activityType.ifBlank { "Профессия не указана" },
                    style = professionStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

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
                StatRow("Чистыми (с налогом)", "${earnedWithTax.toInt()} ₽", isHighlight = true)
                StatRow("Заработано всего", "${totalEarned.toInt()} ₽")
                StatRow("Ожидаемый доход", "${expectedEarnings.toInt()} ₽")
            }
        }

        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(12.dp)
        ) {
            Icon(Icons.Default.Settings, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (profile.isRegistered) "Настройки профиля" else "Создать профиль")
        }

        TextButton(
            onClick = onOpenAppSettings,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Настройки приложения", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private data class ProfileStats(
    val attendedStudents: Int,
    val totalStudents: Int,
    val totalEarned: Double,
    val netEarned: Double,
    val netExpected: Double,
)

private fun computeProfileStats(
    dayData: Map<LocalDate, List<String>>,
    pricePerSession: Double,
    monthlyTax: Double,
): ProfileStats {
    var attended = 0
    var total = 0
    var extrasEarned = 0.0
    var extrasExpected = 0.0
    for ((_, list) in dayData) {
        for (raw in list) {
            when (val session = SessionParser.parse(raw)) {
                is Session.Student -> {
                    total++
                    if (session.attended) attended++
                }
                is Session.Intensive -> {
                    if (session.attended) extrasEarned += session.amount else extrasExpected += session.amount
                }
                is Session.Diagnostics -> {
                    if (session.attended) extrasEarned += session.amount else extrasExpected += session.amount
                }
            }
        }
    }
    val gross = attended * pricePerSession + extrasEarned
    val grossExpected = total * pricePerSession + extrasEarned + extrasExpected
    return ProfileStats(
        attendedStudents = attended,
        totalStudents = total,
        totalEarned = gross,
        netEarned = (gross - monthlyTax).coerceAtLeast(0.0),
        netExpected = (grossExpected - monthlyTax).coerceAtLeast(0.0),
    )
}

@RequiresApi(Build.VERSION_CODES.O)
@Preview(showBackground = true, name = "Profile Light")
@Composable
fun ProfileContentLightPreview() {
    NeiroTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ProfileContentImpl(
                profile = UserProfile(
                    name = "Света",
                    activityType = "Репетитор",
                    pricePerSession = 1500.0,
                    monthlyTaxAmount = 5000.0
                ),
                dayData = emptyMap(),
                onOpenSettings = {},
                onOpenAppSettings = {}
            )
        }
    }
}
