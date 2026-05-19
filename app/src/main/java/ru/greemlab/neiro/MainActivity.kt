package ru.greemlab.neiro

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.profile.ProfileViewModel
import ru.greemlab.neiro.ui.screens.CalendarScreen
import ru.greemlab.neiro.ui.settings.AppSettingsViewModel

/**
 * Главная Activity приложения.
 * Является точкой входа и устанавливает основной контент с использованием Jetpack Compose.
 */
class MainActivity : ComponentActivity() {

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

                // Тёмный «холст» того же цвета, что и системный windowBackground
                // (`@color/splash_background`). За счёт этого момент между
                // системным сплеш-окном и Compose-контентом не виден глазу —
                // фон не меняется, а UI плавно проявляется поверх.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SplashBackground),
                ) {
                    AnimatedVisibility(
                        visible = profile != null,
                        enter = fadeIn(animationSpec = tween(durationMillis = CONTENT_FADE_IN_MS)),
                    ) {
                        CalendarScreen(profileViewModel = profileViewModel)
                    }
                }
            }
        }
    }

    private companion object {
        const val CONTENT_FADE_IN_MS = 500
    }
}

/** Фон, идентичный `R.color.splash_background` — обеспечивает бесшовный переход от системного сплеша. */
private val SplashBackground = Color(0xFF121212)
