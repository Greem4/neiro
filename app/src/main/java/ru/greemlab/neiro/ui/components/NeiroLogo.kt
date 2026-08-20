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
import ru.greemlab.neiro.theme.LogoGradientEnd
import ru.greemlab.neiro.theme.LogoGradientStart
import ru.greemlab.neiro.ui.util.cappedSp

// Знак бренда, а не тема: цвета круга не меняются ни от светлой/тёмной темы,
// ни от выбранной палитры. Brush собирается один раз на файл, а не на каждую
// рекомпозицию заголовка.
private val LogoBrush = Brush.verticalGradient(
    colors = listOf(LogoGradientStart, LogoGradientEnd),
)

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
    modifier: Modifier = Modifier,
) {
    val logoInteraction = remember(onClick) { MutableInteractionSource() }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                .background(brush = LogoBrush, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "N",
                color = Color.White,
                // «N» — часть знака, а не читаемый текст: её размер задан кругом
                // логотипа и системный шрифт его не меняет.
                fontSize = cappedSp((size.value * 0.6f).dp, maxScale = 1f),
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
