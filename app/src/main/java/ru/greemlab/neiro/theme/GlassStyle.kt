package ru.greemlab.neiro.theme

import android.content.Context
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Стеклянный вид поверхностей — тумблер в «Настройки → Внешний вид».
 *
 * Выключен по умолчанию: при `false` всё рисуется ровно как раньше, обычным
 * [Surface] с непрозрачным цветом схемы. Настройка живёт в
 * `AppearancePreferences`, сюда её кладёт `NeiroApp`.
 */
val LocalGlassEnabled = staticCompositionLocalOf { false }

/**
 * ПАРАМЕТРЫ СТЕКЛА (Твикай здесь)
 *
 * Прозрачность подобрана под два случая: с размытием за окном (Android 12+)
 * панель может быть заметно прозрачнее, без него — приходится держать её
 * плотнее, иначе календарь под диалогом перебивает текст.
 */
object GlassStyle {
    val panelCorner: Dp = 24.dp
    val blurRadius: Dp = 36.dp

    /** Затемнение за диалогом: со стеклом оно мягче штатного. */
    const val SCRIM_ALPHA = 0.28f

    // Плотнее, чем просилось бы «красивым стеклом»: сквозь панель просвечивал
    // пёстрый календарь, фон под цифрами получался неоднородным, и суммы
    // приходилось вычитывать. Читаемость важнее прозрачности.
    const val DARK_TOP_ALPHA = 0.82f
    const val DARK_BOTTOM_ALPHA = 0.72f
    const val LIGHT_TOP_ALPHA = 0.86f
    const val LIGHT_BOTTOM_ALPHA = 0.74f

    /** Прибавка непрозрачности там, где размытия нет, — ради читаемости текста. */
    const val NO_BLUR_EXTRA_ALPHA = 0.16f

    const val BORDER_TOP_ALPHA = 0.34f
    const val BORDER_BOTTOM_ALPHA = 0.06f
    val borderWidth: Dp = 1.dp
}

/**
 * Панель диалога. Со стеклом — полупрозрачное матовое стекло с бликом по краю,
 * без стекла — обычный [Surface] цвета `surface`.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlassStyle.panelCorner),
    content: @Composable () -> Unit,
) {
    if (!LocalGlassEnabled.current) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            content = content,
        )
        return
    }

    ApplyDialogGlass()
    Box(
        modifier = modifier
            .clip(shape)
            .background(glassPanelBrush())
            .glassBorder(shape),
    ) {
        content()
    }
}

/** Цвет контейнера для готовых диалогов Material (`AlertDialog` и подобные). */
@Composable
fun glassContainerColor(): Color {
    val scheme = MaterialTheme.colorScheme
    if (!LocalGlassEnabled.current) return scheme.surface
    // База — `surface`, а не `surfaceVariant`: тот заметно светлее фона, и
    // панель поверх тёмного календаря выходила серой, съедая контраст у сумм.
    val alpha = if (scheme.surface.luminance() < 0.5f) {
        GlassStyle.DARK_TOP_ALPHA
    } else {
        GlassStyle.LIGHT_TOP_ALPHA
    }
    return scheme.surface.copy(alpha = alpha + extraAlphaWithoutBlur())
}

/**
 * Плашка внутри стеклянной панели — вложенная группа строк (например, разбивка
 * зарплаты в диалоге финансов).
 *
 * Без стекла — приглушённая `surfaceVariant`, как было. Со стеклом панель уже
 * полупрозрачная, и та же заливка поверх неё читается мутным пятном: берём
 * тонкий светлый слой, который только отделяет группу от фона, не перекрывая
 * размытие под диалогом.
 */
@Composable
fun glassNestedSurfaceColor(): Color {
    val scheme = MaterialTheme.colorScheme
    if (!LocalGlassEnabled.current) {
        return scheme.surfaceVariant.copy(alpha = MutedSurfaceAlpha)
    }
    val dark = scheme.surface.luminance() < 0.5f
    val tint = if (dark) Color.White else scheme.onSurface
    return tint.copy(alpha = if (dark) GLASS_NESTED_DARK_ALPHA else GLASS_NESTED_LIGHT_ALPHA)
}

private const val GLASS_NESTED_DARK_ALPHA = 0.07f
private const val GLASS_NESTED_LIGHT_ALPHA = 0.05f

/**
 * Плашка под блоком цифр — не подсветка группы, а глушитель фона.
 *
 * Панель диалога пропускает календарь под собой, и пёстрое пятно за суммами
 * тянет взгляд на себя. Здесь поверх панели кладётся ещё один слой `surface`:
 * вместе они дают почти непрозрачный фон (0.82 + 0.6 ≈ 0.93), фон под цифрами
 * гаснет, а стекло остаётся видно по краям панели и за заголовком.
 *
 * Без стекла гасить нечего — панель и так непрозрачная, и плашка остаётся
 * прежним приглушённым блоком, только чтобы отделить группу.
 */
@Composable
fun glassReadingSurfaceColor(): Color {
    val scheme = MaterialTheme.colorScheme
    if (!LocalGlassEnabled.current) {
        return scheme.surfaceVariant.copy(alpha = MutedSurfaceAlpha)
    }
    val dark = scheme.surface.luminance() < 0.5f
    return scheme.surface.copy(
        alpha = if (dark) GLASS_READING_DARK_ALPHA else GLASS_READING_LIGHT_ALPHA,
    )
}

private const val GLASS_READING_DARK_ALPHA = 0.60f
private const val GLASS_READING_LIGHT_ALPHA = 0.64f

/**
 * Разделитель внутри стеклянной панели.
 *
 * `outlineVariant` рассчитан на непрозрачную поверхность и на просвечивающем
 * стекле почти пропадает — со стеклом берём тот же светлый тон, что у блика.
 */
@Composable
fun glassDividerColor(): Color {
    val scheme = MaterialTheme.colorScheme
    if (!LocalGlassEnabled.current) return scheme.outlineVariant.copy(alpha = 0.5f)
    val dark = scheme.surface.luminance() < 0.5f
    val tint = if (dark) Color.White else scheme.onSurface
    return tint.copy(alpha = GLASS_DIVIDER_ALPHA)
}

private const val GLASS_DIVIDER_ALPHA = 0.14f

/**
 * Стекло для мелкого элемента, висящего над лентой, — кнопки или чипа.
 *
 * Прозрачнее и на тон светлее панели: панель закрывает пол-экрана и обязана
 * держать текст читаемым, а кнопка висит над календарём, и при плотности
 * панели смотрится её забытым куском. Акцент кнопке даёт цвет содержимого,
 * а не заливка.
 *
 * Без стекла возвращает `secondaryContainer`: кнопка остаётся ровно такой,
 * какой была.
 */
@Composable
fun glassControlColor(): Color {
    val scheme = MaterialTheme.colorScheme
    if (!LocalGlassEnabled.current) return scheme.secondaryContainer
    val dark = scheme.surface.luminance() < 0.5f
    val base = if (dark) scheme.surfaceVariant else scheme.surface
    return base.copy(alpha = (GLASS_CONTROL_ALPHA + extraAlphaWithoutBlur()).coerceAtMost(1f))
}

private const val GLASS_CONTROL_ALPHA = 0.5f

/** Блик по краю панели: сверху ярче, снизу почти незаметен. */
@Composable
fun Modifier.glassBorder(shape: Shape): Modifier {
    if (!LocalGlassEnabled.current) return this
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val edge = if (dark) Color.White else scheme.onSurface
    return border(
        width = GlassStyle.borderWidth,
        brush = Brush.verticalGradient(
            listOf(
                edge.copy(alpha = GlassStyle.BORDER_TOP_ALPHA),
                edge.copy(alpha = GlassStyle.BORDER_BOTTOM_ALPHA),
            ),
        ),
        shape = shape,
    )
}

/**
 * Размытие календаря за окном диалога и мягкий скрим.
 * Размытие есть только на Android 12+ и только если система его не отключила
 * (энергосбережение, слабое устройство) — иначе остаётся полупрозрачность.
 */
@Composable
fun ApplyDialogGlass(radius: Dp = GlassStyle.blurRadius) {
    val view = LocalView.current
    val density = LocalDensity.current
    val enabled = LocalGlassEnabled.current

    LaunchedEffect(view, enabled, radius) {
        if (!enabled) return@LaunchedEffect
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        window.setDimAmount(GlassStyle.SCRIM_ALPHA)
        if (!isBlurSupported(view.context)) return@LaunchedEffect
        window.applyBlurBehind(with(density) { radius.roundToPx() })
    }
}

@Composable
private fun glassPanelBrush(): Brush {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val base = scheme.surface
    val extra = extraAlphaWithoutBlur()
    val top = if (dark) GlassStyle.DARK_TOP_ALPHA else GlassStyle.LIGHT_TOP_ALPHA
    val bottom = if (dark) GlassStyle.DARK_BOTTOM_ALPHA else GlassStyle.LIGHT_BOTTOM_ALPHA
    return Brush.verticalGradient(
        listOf(
            base.copy(alpha = (top + extra).coerceAtMost(1f)),
            base.copy(alpha = (bottom + extra).coerceAtMost(1f)),
        ),
    )
}

@Composable
private fun extraAlphaWithoutBlur(): Float {
    val context = LocalContext.current
    val supported = remember(context) { isBlurSupported(context) }
    return if (supported) 0f else GlassStyle.NO_BLUR_EXTRA_ALPHA
}

private fun isBlurSupported(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val windowManager = context.getSystemService(WindowManager::class.java) ?: return false
    return windowManager.isCrossWindowBlurEnabled
}

@RequiresApi(Build.VERSION_CODES.S)
private fun Window.applyBlurBehind(radiusPx: Int) {
    addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
    attributes = attributes.also { params -> params.blurBehindRadius = radiusPx }
}
