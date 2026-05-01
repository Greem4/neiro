package ru.greemlab.neiro.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Конфигурация цветовой схемы для темной темы.
 */
private val DarkColorScheme = darkColorScheme(
    background = DarkBackground,
    surface = DarkSurface,
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    onBackground = Color.White,
    onSurface = Color.White
)

/**
 * Конфигурация цветовой схемы для светлой темы.
 */
private val LightColorScheme = lightColorScheme(
    background = LightBackground,
    surface = LightSurface,
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F)
)

/**
 * Основная тема приложения Neiro.
 * Обеспечивает применение цветовой схемы и типографики ко всем вложенным Composable-функциям.
 * 
 * @param darkTheme Использовать ли темную тему. По умолчанию определяется системными настройками.
 * @param dynamicColor Использовать ли динамические цвета (Material You) на Android 12+.
 * @param content Вложенный UI контент.
 */
@Composable
fun NeiroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // По умолчанию отключено для сохранения фирменного стиля
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
