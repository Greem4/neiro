package ru.greemlab.neiro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.THEME_DARK
import ru.greemlab.neiro.data.THEME_LIGHT
import ru.greemlab.neiro.notifications.SessionNotificationCoordinator
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.profile.ProfileViewModel
import ru.greemlab.neiro.ui.screens.CalendarScreen
import ru.greemlab.neiro.ui.settings.AppSettingsViewModel

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_DATE = "open_date"
        const val EXTRA_HIGHLIGHT_SLOT_KEY = "highlight_slot_key"
    }

    private var openDate by mutableStateOf<String?>(null)
    private var highlightSlotKey by mutableStateOf<String?>(null)
    private var notificationDeepLinkVersion by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        applyNotificationExtras(intent)

        setContent {
            val deepLinkVersion = notificationDeepLinkVersion
            NeiroApp(
                openDateFromNotification = openDate,
                highlightSlotKeyFromNotification = highlightSlotKey,
                notificationDeepLinkVersion = deepLinkVersion,
            )
            RequestNotificationPermissionIfNeeded()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyNotificationExtras(intent)
    }

    private fun applyNotificationExtras(source: Intent?) {
        openDate = source?.getStringExtra(EXTRA_OPEN_DATE)
        highlightSlotKey = source?.getStringExtra(EXTRA_HIGHLIGHT_SLOT_KEY)
        notificationDeepLinkVersion++
    }
}

@Composable
private fun RequestNotificationPermissionIfNeeded() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        CheckDueDigestsOnAppOpen()
        return
    }

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
    notificationDeepLinkVersion: Int = 0,
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
            notificationDeepLinkVersion = notificationDeepLinkVersion,
        )
    }
}
