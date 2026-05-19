package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Логотип приложения: синий круг с буквой «N» и опционально подпись «Neiro».
 *
 * @param onClick Если задан — кликабелен **только круг с «N»**, не подпись.
 *                На главном экране передаётся [CalendarHeader.onMenuClick]
 *                для открытия боковой панели профиля.
 */
@Composable
fun NeiroLogo(
    size: Dp = 32.dp,
    showText: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val logoInteraction = remember(onClick) { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = logoInteraction,
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                )
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF24A1DE), Color(0xFF1E96C8))
                    ),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "N",
                color = Color.White,
                fontSize = (size.value * 0.6).sp,
                fontWeight = FontWeight.Black
            )
        }
        
        if (showText) {
            Text(
                text = "Neiro",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                letterSpacing = (-0.5).sp
            )
        }
    }
}
