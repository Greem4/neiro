package ru.greemlab.neiro

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.profile.ProfileViewModel
import ru.greemlab.neiro.ui.screens.CalendarScreen

/**
 * Главная Activity приложения.
 * Является точкой входа и устанавливает основной контент с использованием Jetpack Compose.
 */
class MainActivity : ComponentActivity() {
    
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Установка UI контента
        setContent {
            NeiroTheme {
                val profileViewModel: ProfileViewModel = viewModel()
                val profile by profileViewModel.userProfile.collectAsState()

                if (profile != null) {
                    CalendarScreen(profileViewModel = profileViewModel)
                }
            }
        }
    }
}
