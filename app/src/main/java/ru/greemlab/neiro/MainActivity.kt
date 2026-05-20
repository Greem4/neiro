package ru.greemlab.neiro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.greemlab.neiro.data.THEME_DARK
import ru.greemlab.neiro.data.THEME_LIGHT
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.profile.ProfileViewModel
import ru.greemlab.neiro.ui.screens.CalendarScreen
import ru.greemlab.neiro.ui.settings.AppSettingsViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Системный splash ставится до super.onCreate — иначе будет чёрная вспышка.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            NeiroApp()
        }
    }
}

@Composable
private fun NeiroApp() {
    val settingsViewModel: AppSettingsViewModel = viewModel()
    val profileViewModel: ProfileViewModel = viewModel()
    val theme by settingsViewModel.theme.collectAsState()
    val systemDark = isSystemInDarkTheme()

    val isDarkTheme = when (theme) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> systemDark
    }

    NeiroTheme(darkTheme = isDarkTheme) {
        CalendarScreen(profileViewModel = profileViewModel)
    }
}
