package ru.greemlab.neiro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.THEME_DARK
import ru.greemlab.neiro.notifications.SessionNotificationCoordinator
import ru.greemlab.neiro.data.THEME_LIGHT
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.profile.ProfileViewModel
import ru.greemlab.neiro.ui.screens.CalendarScreen
import ru.greemlab.neiro.ui.settings.AppSettingsViewModel

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_DATE = "open_date"
        const val EXTRA_HIGHLIGHT_SLOT_KEY = "highlight_slot_key"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Системный splash ставится до super.onCreate — иначе будет чёрная вспышка.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val openDate = intent?.getStringExtra(EXTRA_OPEN_DATE)
        val highlightSlotKey = intent?.getStringExtra(EXTRA_HIGHLIGHT_SLOT_KEY)

        setContent {
            NeiroApp(
                openDateFromNotification = openDate,
                highlightSlotKeyFromNotification = highlightSlotKey,
            )
            RequestNotificationPermissionIfNeeded()
            CheckDueDigestsOnAppOpen()
        }
    }
}

@Composable
private fun RequestNotificationPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            scope.launch {
                SessionNotificationCoordinator.checkDueDigestsOnAppOpen(appContext)
            }
        }
    }

    LaunchedEffect(granted) {
        if (!granted) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            SessionNotificationCoordinator.checkDueDigestsOnAppOpen(appContext)
        }
    }
}

@Composable
private fun CheckDueDigestsOnAppOpen() {
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        SessionNotificationCoordinator.checkDueDigestsOnAppOpen(appContext)
    }
}

@Composable
private fun NeiroApp(
    openDateFromNotification: String? = null,
    highlightSlotKeyFromNotification: String? = null,
) {
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
        CalendarScreen(
            profileViewModel = profileViewModel,
            openDateFromNotification = openDateFromNotification,
            highlightSlotKeyFromNotification = highlightSlotKeyFromNotification,
        )
    }
}
