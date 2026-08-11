package ru.greemlab.neiro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import ru.greemlab.neiro.data.THEME_DARK
import ru.greemlab.neiro.data.THEME_LIGHT
import ru.greemlab.neiro.notifications.SessionNotificationCoordinator
import ru.greemlab.neiro.theme.ApplySystemBars
import ru.greemlab.neiro.theme.NeiroTheme
import ru.greemlab.neiro.ui.profile.ProfileViewModel
import ru.greemlab.neiro.ui.screens.CalendarScreen
import ru.greemlab.neiro.ui.settings.AppSettingsViewModel

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_DATE = "open_date"
        const val EXTRA_HIGHLIGHT_SLOT_KEY = "highlight_slot_key"

        /** Открыть «О программе» — уведомление о новой версии (пакет `update`). */
        const val EXTRA_OPEN_ABOUT = "open_about"
        private const val STATE_OPEN_DATE = "neiro.state.open_date"
        private const val STATE_HIGHLIGHT_SLOT_KEY = "neiro.state.highlight_slot_key"
        private const val STATE_DEEP_LINK_VERSION = "neiro.state.deep_link_version"
    }

    private var openDate by mutableStateOf<String?>(null)
    private var highlightSlotKey by mutableStateOf<String?>(null)
    private var notificationDeepLinkVersion by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            openDate = savedInstanceState.getString(STATE_OPEN_DATE)
            highlightSlotKey = savedInstanceState.getString(STATE_HIGHLIGHT_SLOT_KEY)
            notificationDeepLinkVersion = savedInstanceState.getInt(STATE_DEEP_LINK_VERSION, 0)
        } else {
            applyNotificationExtras(intent)
        }

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        openDate?.let { outState.putString(STATE_OPEN_DATE, it) }
        highlightSlotKey?.let { outState.putString(STATE_HIGHLIGHT_SLOT_KEY, it) }
        outState.putInt(STATE_DEEP_LINK_VERSION, notificationDeepLinkVersion)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyNotificationExtras(intent)
    }

    private fun applyNotificationExtras(source: Intent?) {
        val newOpenDate = source?.getStringExtra(EXTRA_OPEN_DATE)
        val newHighlight = source?.getStringExtra(EXTRA_HIGHLIGHT_SLOT_KEY)
        val changed = newOpenDate != openDate || newHighlight != highlightSlotKey
        openDate = newOpenDate
        highlightSlotKey = newHighlight
        if (changed) notificationDeepLinkVersion++
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

    LaunchedEffect(Unit) {
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
    val theme by settingsViewModel.theme.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()

    val isDarkTheme = when (theme) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> systemDark
    }

    NeiroTheme(darkTheme = isDarkTheme) {
        ApplySystemBars(darkTheme = isDarkTheme)
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            CalendarScreen(
                profileViewModel = profileViewModel,
                openDateFromNotification = openDateFromNotification,
                highlightSlotKeyFromNotification = highlightSlotKeyFromNotification,
                notificationDeepLinkVersion = notificationDeepLinkVersion,
            )
        }
    }
}
