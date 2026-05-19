package ru.greemlab.neiro

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.profile.ProfileViewModel
import ru.greemlab.neiro.ui.screens.CalendarScreen
import ru.greemlab.neiro.ui.settings.AppSettingsViewModel

/**
 * Главная Activity приложения.
 *
 * Использует SplashScreen API: системный сплеш с launcher-иконкой удерживается до
 * загрузки профиля, а затем сама иконка «выстреливает» по своей же диагонали
 * (вправо-вверх — туда, куда направлена черта буквы N) и растворяется,
 * открывая UI без шва.
 */
class MainActivity : ComponentActivity() {

    /** Управляет тем, держим ли мы системный сплеш на экране. */
    private var keepSplashOnScreen: Boolean = true

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { keepSplashOnScreen }
        splashScreen.setOnExitAnimationListener(::animateSplashExit)

        setContent {
            val settingsViewModel: AppSettingsViewModel = viewModel()
            val theme by settingsViewModel.theme.collectAsState()

            val isDarkTheme = when (theme) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }

            NeiroTheme(darkTheme = isDarkTheme) {
                val profileViewModel: ProfileViewModel = viewModel()
                val profile by profileViewModel.userProfile.collectAsState()

                // Как только профиль загрузился — даём системе снять сплеш.
                // Минимальная задержка нужна, чтобы анимация всегда успела
                // проиграться полностью, даже на горячем кэше DataStore.
                LaunchedEffect(profile) {
                    if (profile != null) {
                        delay(SPLASH_MIN_HOLD_MS)
                        keepSplashOnScreen = false
                    }
                }

                // Тёмный «холст» того же цвета, что и сплеш — за счёт этого
                // в момент удаления сплеш-вью не видно перепада фона.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SplashBackground),
                ) {
                    if (profile != null) {
                        CalendarScreen(profileViewModel = profileViewModel)
                    }
                }
            }
        }
    }

    /**
     * Анимация выхода со сплеша: буква N «прицеливается» (короткий замах назад)
     * и стреляет вдоль собственной диагонали — вправо и вверх, по пути
     * вращаясь и уменьшаясь. Фон гаснет в самом конце, открывая UI.
     */
    private fun animateSplashExit(provider: SplashScreenViewProvider) {
        val splashView = provider.view
        val iconView = provider.iconView

        iconView.pivotX = iconView.width / 2f
        iconView.pivotY = iconView.height / 2f

        val width = splashView.width.toFloat()
        val height = splashView.height.toFloat()

        // Конечная точка полёта N: вправо-вверх, заведомо за пределы экрана,
        // вдоль диагонали ~30° от горизонтали (как наклон правой черты буквы).
        val flyToX = width * 0.85f
        val flyToY = -height * 0.55f

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = EXIT_DURATION_MS
            interpolator = PathInterpolator(0.16f, 0.0f, 0.30f, 1.0f)
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                applyFlyOut(iconView, splashView, t, flyToX, flyToY)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    provider.remove()
                }
            })
        }
        animator.start()
    }

    private fun applyFlyOut(
        iconView: View,
        splashView: View,
        t: Float,
        flyToX: Float,
        flyToY: Float,
    ) {
        if (t < REV_UP_END) {
            // Замах: чуть наклоняется назад против хода полёта, увеличивается.
            val p = t / REV_UP_END
            iconView.scaleX = 1f + 0.08f * p
            iconView.scaleY = 1f + 0.08f * p
            iconView.translationX = 0f
            iconView.translationY = 0f
            iconView.rotation = -5f * p
            iconView.alpha = 1f
            splashView.alpha = 1f
        } else {
            // Выстрел: ускоренно летит к flyToX/flyToY, вращается по часовой,
            // уменьшается и затухает. Фон начинает гаснуть только в конце.
            val raw = (t - REV_UP_END) / (1f - REV_UP_END)
            val eased = raw * raw // ease-in для ощущения резкого старта
            iconView.translationX = flyToX * eased
            iconView.translationY = flyToY * eased
            iconView.scaleX = 1.08f - 0.88f * eased
            iconView.scaleY = iconView.scaleX
            iconView.rotation = -5f + 30f * eased
            iconView.alpha = (1f - eased * 1.25f).coerceAtLeast(0f)
            splashView.alpha = if (raw < BG_FADE_START) {
                1f
            } else {
                val fade = (raw - BG_FADE_START) / (1f - BG_FADE_START)
                (1f - AccelerateInterpolator().getInterpolation(fade)).coerceIn(0f, 1f)
            }
        }
    }

    private companion object {
        /** Минимальное время удержания сплеша — чтобы анимация всегда играла полностью. */
        const val SPLASH_MIN_HOLD_MS = 350L

        /** Общая длительность вылета иконки. */
        const val EXIT_DURATION_MS = 750L

        /** Доля времени на «замах» перед вылетом. */
        const val REV_UP_END = 0.18f

        /** На какой доле полёта начинает гаснуть фон сплеша. */
        const val BG_FADE_START = 0.55f
    }
}

/** Фон, идентичный `R.color.splash_background` — бесшовный переход. */
private val SplashBackground = Color(0xFF121212)
