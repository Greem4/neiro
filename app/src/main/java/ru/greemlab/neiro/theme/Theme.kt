package ru.greemlab.neiro.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * Тема приложения Neiro.
 *
 * Раздаёт три вещи: Material-схему, семантические цвета приложения
 * ([neiroSemanticColors]) и саму палитру ([LocalNeiroPalette]) — последнюю
 * только для экрана настроек, где надо отметить выбранное оформление.
 *
 * @param darkTheme Тёмная тема. Считается из [ThemeMode], а не из системы:
 *                  выбор в настройках приложения важнее системного.
 * @param palette Оформление. По умолчанию фирменное — пока выбора нет в UI,
 *                поведение ровно как раньше.
 * @param content Контент внутри темы.
 */
@Composable
fun NeiroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    palette: NeiroPalette = NeiroPalettes.Default,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = resolveColorScheme(palette.colors, darkTheme),
        typography = Typography,
    ) {
        CompositionLocalProvider(
            LocalNeiroPalette provides palette,
            LocalNeiroSemanticColors provides palette.semanticColors(darkTheme),
        ) {
            content()
        }
    }
}

/**
 * Превращает источник цвета палитры в готовую Material-схему.
 *
 * Новый вид палитры (например, схема из seed-цвета) добавляется веткой здесь
 * и подтипом [NeiroColorSource] — больше нигде.
 */
@Composable
private fun resolveColorScheme(
    source: NeiroColorSource,
    darkTheme: Boolean,
): ColorScheme = when (source) {
    is NeiroColorSource.Fixed -> if (darkTheme) source.dark else source.light

    is NeiroColorSource.Wallpaper ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (darkTheme) source.fallback.dark else source.fallback.light
        }
}
