package ru.greemlab.neiro.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Семантические цвета приложения (статусы занятий, суммы, акценты уведомлений).
 *
 * В отличие от [androidx.compose.material3.ColorScheme], эти цвета не входят в
 * Material-палитру, но тоже зависят от темы: пастельные варианты, читаемые на
 * тёмном фоне, на светлом превращаются в нечитаемый текст, поэтому у каждого
 * есть пара (`*Light`/`*Dark` в [Color.kt]). Набор под текущую яркость отдаёт
 * [NeiroPalette], а раздаёт [NeiroTheme] — то есть цвета следуют теме,
 * выбранной в приложении, а не системной (чем грешил `isSystemInDarkTheme()`
 * в точках вызова).
 *
 * Здесь лежит **весь** цвет приложения, которого нет в Material-схеме: если для
 * нового элемента понадобился оттенок, его место тут, а не `Color(0xFF…)` по
 * месту — иначе он не переключится вместе с палитрой.
 */
@Immutable
data class NeiroSemanticColors(
    /** «Пришёл и оплатил» / суммы заработанного. */
    val profit: Color,
    /** «Подтвердил визит» / суммы ожидаемого. */
    val expected: Color,
    /** Шапка записи расписания, «заработано», иконки успеха. */
    val scheduleHeader: Color,
    /** Имя ожидающего ученика. */
    val statusExpected: Color,
    /** Отменённое занятие. */
    val statusCancelled: Color,
    /** Имя диагностики в слоте расписания. */
    val diagnostics: Color,
    /** Акцент «перенос» в in-app уведомлениях. */
    val rescheduleNotification: Color,
    /** Подпись выходного дня (Сб/Вс) в шапке календаря. */
    val weekend: Color,
    /** Линия текущего времени на шкале дня. */
    val nowLine: Color,
    /** Подложка круглой иконки статуса в строке расписания. */
    val statusIconSurface: Color,
)

val LightSemanticColors = NeiroSemanticColors(
    profit = ProfitLight,
    expected = ExpectedLight,
    scheduleHeader = ScheduleHeaderLight,
    statusExpected = StatusExpectedLight,
    statusCancelled = StatusCancelledLight,
    diagnostics = DiagnosticsIndigo,
    rescheduleNotification = RescheduleNotificationLight,
    weekend = WeekendLight,
    nowLine = NowLineRed,
    statusIconSurface = StatusIconSurface,
)

val DarkSemanticColors = NeiroSemanticColors(
    profit = ProfitDark,
    expected = ExpectedDark,
    scheduleHeader = ScheduleHeaderDark,
    statusExpected = StatusExpectedDark,
    statusCancelled = StatusCancelledDark,
    diagnostics = DiagnosticsIndigo,
    rescheduleNotification = RescheduleNotificationDark,
    weekend = WeekendDark,
    nowLine = NowLineRed,
    statusIconSurface = StatusIconSurface,
)

val LocalNeiroSemanticColors = staticCompositionLocalOf { LightSemanticColors }

/**
 * Альфа «приглушённой» поверхности (карточки/плашки на `surfaceVariant`).
 * Единое значение для всего приложения: раньше встречались 0.28–0.45,
 * из-за чего одинаковые по роли плашки отличались по тону.
 */
const val MutedSurfaceAlpha = 0.3f

/**
 * Лестница поверхностей — насколько каждый следующий уровень отделяется от
 * того, что под ним.
 *
 * Значение — доля `surfaceVariant`, положенная поверх родителя. Ступени
 * подобраны от чёрного фона так, чтобы на амоледе получилась ровная лестница
 * (#000 → #131519 → #1A1D23 → #22262E): раньше уровни задавались вразнобой
 * (0.28 у одной панели, 0.3 у другой, `background` под плашкой дня), близкие
 * тёмно-серые сливались, а плашка дня проваливалась в чёрный.
 *
 * Доля, а не готовый цвет, — чтобы лестница работала и на палитре из обоев,
 * где `surfaceVariant` приходит от системы.
 */
object NeiroSurfaceAlpha {
    /** Панель поверх фона: шапка месяца, полоса вкладок. */
    const val PANEL = 0.41f

    /** Карточка внутри панели: плашка дня. */
    const val CARD = 0.26f

    /** Плитка внутри панели или карточки: счётчики, суммы. */
    const val TILE = 0.40f
}

/** Текущая семантическая палитра — см. [NeiroSemanticColors]. */
val neiroSemanticColors: NeiroSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalNeiroSemanticColors.current
