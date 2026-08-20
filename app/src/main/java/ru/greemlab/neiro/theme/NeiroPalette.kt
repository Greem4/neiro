package ru.greemlab.neiro.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Откуда палитра берёт Material-схему.
 *
 * Вынесено в отдельный тип, потому что источников больше одного и они ведут
 * себя по-разному: фирменные цвета зашиты в приложении, а Material You читает
 * их у системы в момент отрисовки. Новый источник (например, схема,
 * сгенерированная из выбранного пользователем seed-цвета) добавляется новым
 * подтипом и веткой в `resolveColorScheme` в [Theme.kt] — трогать сами палитры
 * и точки использования цвета при этом не нужно.
 */
@Immutable
sealed interface NeiroColorSource {

    /** Фиксированная пара схем, нарисованная вручную. */
    @Immutable
    data class Fixed(
        val light: ColorScheme,
        val dark: ColorScheme,
    ) : NeiroColorSource

    /**
     * Material You: цвета берутся из обоев (Android 12+).
     *
     * @param fallback чем рисовать там, где динамических цветов нет.
     */
    @Immutable
    data class Wallpaper(
        val fallback: Fixed,
    ) : NeiroColorSource
}

/**
 * Одно оформление приложения целиком: Material-схема плюс семантические цвета
 * для обеих яркостей.
 *
 * Палитра — то, что выбирают в настройках; [ThemeMode] отвечает только за
 * яркость. Поэтому каждая палитра обязана уметь и светлую, и тёмную: выбрать
 * оформление и потерять тёмную тему пользователь не должен.
 *
 * Семантика ([NeiroSemanticColors]) входит в палитру, а не выводится из
 * Material-схемы: «пришёл и оплатил» обязан оставаться узнаваемым цветом
 * статуса в любом оформлении, иначе смена палитры молча переназначает смысл
 * цветов в расписании.
 */
@Immutable
data class NeiroPalette(
    /** Идентификатор для хранения выбора. Не переименовывать — уедет в prefs. */
    val id: String,
    /** Название для экрана настроек. */
    val title: String,
    val colors: NeiroColorSource,
    val lightSemantic: NeiroSemanticColors,
    val darkSemantic: NeiroSemanticColors,
) {
    fun semanticColors(dark: Boolean): NeiroSemanticColors =
        if (dark) darkSemantic else lightSemantic
}

/** Фирменная тёмная схема Neiro. */
val NeiroDarkColorScheme: ColorScheme = darkColorScheme(
    background = DarkBackground,
    surface = DarkSurface,
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    error = ErrorDark,
    onBackground = OnSurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
)

/** Фирменная светлая схема Neiro. */
val NeiroLightColorScheme: ColorScheme = lightColorScheme(
    background = LightBackground,
    surface = LightSurface,
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    error = ErrorLight,
    onBackground = OnSurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
)

private val NeiroFixedColors = NeiroColorSource.Fixed(
    light = NeiroLightColorScheme,
    dark = NeiroDarkColorScheme,
)

/**
 * Реестр доступных оформлений.
 *
 * Чтобы добавить палитру: описать её цвета в [Color.kt], собрать
 * [NeiroSemanticColors] для светлой и тёмной яркости, добавить объект сюда и
 * внести его в [all]. Экран настроек берёт список отсюда и ничего про
 * конкретные палитры не знает.
 */
object NeiroPalettes {

    /** Фирменное оформление — то, что было до появления выбора. */
    val Neiro = NeiroPalette(
        id = "neiro",
        title = "Neiro",
        colors = NeiroFixedColors,
        lightSemantic = LightSemanticColors,
        darkSemantic = DarkSemanticColors,
    )

    /**
     * Material You: Material-цвета от обоев, семантика — фирменная.
     *
     * Семантика намеренно не подстраивается под обои: цвет статуса занятия
     * несёт смысл, а не оформление.
     */
    val Wallpaper = NeiroPalette(
        id = "wallpaper",
        title = "Обои системы",
        colors = NeiroColorSource.Wallpaper(fallback = NeiroFixedColors),
        lightSemantic = LightSemanticColors,
        darkSemantic = DarkSemanticColors,
    )

    /** Чем рисуем, пока пользователь ничего не выбрал. */
    val Default = Neiro

    val all: List<NeiroPalette> = listOf(Neiro, Wallpaper)

    /** Неизвестный или пустой id читается как [Default]. */
    fun byId(id: String?): NeiroPalette = all.firstOrNull { it.id == id } ?: Default
}

/**
 * Текущая палитра. Нужна экрану настроек, чтобы отметить выбранную; за самим
 * цветом ходить сюда не надо — для этого есть `MaterialTheme.colorScheme` и
 * [neiroSemanticColors].
 */
val LocalNeiroPalette = staticCompositionLocalOf { NeiroPalettes.Default }
