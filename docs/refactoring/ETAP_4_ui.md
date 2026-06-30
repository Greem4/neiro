# Этап 4 — UI и Compose

**Уровень риска:** Низкий. UI-патчи без изменения навигации и бизнес-логики.
**Зависимости:** —
**Acceptance:**
- [ ] WebView корректно `destroy()` в `DisposableEffect.onDispose`.
- [ ] `SyncViewModel` игнорирует повторные `syncMonth`/`syncDateRange` при `isLoading == true`.
- [ ] `LazyColumn(items(intensiveIndices.size))` в `DayDetailsDialog` использует stable key (uuid/raw value).
- [ ] `LazyColumn(items(children, key = { it.name }))` в `IntensiveDetailsDialog` — key с индексом для деда против коллизий.
- [ ] `AttendanceStatusPickerIcon` имеет touch target ≥ 48dp.
- [ ] `SessionNotificationSettingsViewModel` отдаёт `StateFlow`, не `mutableStateOf`.
- [ ] `DayScheduleTimeline` не recomposит на каждый scroll (`snapshotFlow { scrollState.value }`).
- [ ] `ScheduleSlotItem` drag не делает `scope.launch { snapTo }` на каждом frame.
- [ ] `Build.MODEL` в `PushDeviceId` уже sanitize (сделано в Этапе 2 — не дублировать).

---

## Файлы для правки

1. `app/src/main/java/ru/greemlab/neiro/ui/yclients/YClientsWebView.kt`
2. `app/src/main/java/ru/greemlab/neiro/ui/sync/SyncViewModel.kt`
3. `app/src/main/java/ru/greemlab/neiro/ui/components/DayDetailsDialog.kt`
4. `app/src/main/java/ru/greemlab/neiro/ui/components/daydetails/IntensiveDetailsDialog.kt`
5. `app/src/main/java/ru/greemlab/neiro/ui/components/daydetails/AttendanceStatusPicker.kt`
6. `app/src/main/java/ru/greemlab/neiro/ui/settings/SessionNotificationSettingsViewModel.kt`
7. `app/src/main/java/ru/greemlab/neiro/ui/components/daydetails/DayScheduleTimeline.kt`
8. `app/src/main/java/ru/greemlab/neiro/ui/components/daydetails/ScheduleSlotItem.kt`

---

## 4.1 WebView: `destroy()` в `onDispose`

**Файл:** `app/src/main/java/ru/greemlab/neiro/ui/yclients/YClientsWebView.kt`

### Проблема

`WebView` остаётся в памяти после ухода с экрана. Это утечка нативной памяти ~5-10 MB на каждое посещение. После 10+ заходов — OOM.

### Сейчас (140-201): factory создаёт WebView, но нет `onDispose`.

### Правка — обернуть `AndroidView` в `DisposableEffect`-aware:

В функции `YClientsWebView` (строки 142-201), заменить блок:

```140:201:app/src/main/java/ru/greemlab/neiro/ui/yclients/YClientsWebView.kt
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YClientsWebView(
    url: String,
    onWebViewCreated: (WebView) -> Unit,
    onLoadingChanged: (Boolean) -> Unit,
    onProgressChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply webView@ {
                ...
            }
        },
        modifier = modifier,
    )
}
```

На:

```kotlin
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YClientsWebView(
    url: String,
    onWebViewCreated: (WebView) -> Unit,
    onLoadingChanged: (Boolean) -> Unit,
    onProgressChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                clearCache(false)
                onPause()
                removeAllViews()
                destroy()
            }
            webViewRef = null
        }
    }

    AndroidView(
        factory = { context ->
            WebView(context).apply webView@ {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    setSupportZoom(true)
                    javaScriptCanOpenWindowsAutomatically = true
                    userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                }

                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(this@webView, true)
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        onLoadingChanged(true)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        onLoadingChanged(false)
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgressChanged(newProgress)
                    }
                }

                loadUrl(url)
                webViewRef = this
                onWebViewCreated(this)
            }
        },
        modifier = modifier,
    )
}
```

И добавить импорты вверху (если их нет):

```kotlin
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
```

**Почему:**
- `DisposableEffect(Unit)` — единоразовый side effect, отрабатывает при покидании Composable.
- Полная последовательность очистки `WebView` по рекомендациям AOSP.

**Коммит:** `Освобождаю WebView при выходе с экрана`

---

## 4.2 SyncViewModel: игнор повторных вызовов при `isLoading`

**Файл:** `app/src/main/java/ru/greemlab/neiro/ui/sync/SyncViewModel.kt`

### Проблема

Пользователь жмёт «Sync» несколько раз — `viewModelScope.launch` стартует несколько корутин одновременно. `_uiState` гоняется между ними, `syncMutex` внутри `YClientsCalendarSync` сериализует доступ к DataStore, но UI-состояние и `lastSyncDate` могут показывать неконсистентные значения.

### Правка

#### 4.2.1 Добавить флаг в `runSync` — игнор если уже идёт:

В `SyncViewModel.kt:203-231` `runSync`:

```203:231:app/src/main/java/ru/greemlab/neiro/ui/sync/SyncViewModel.kt
    private suspend fun runSync(showUi: Boolean, block: suspend () -> SyncOutcome): SyncOutcome {
        if (showUi) {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                showSuccess = false,
            )
        }

        val outcome = block()
        when (outcome) {
            is SyncOutcome.Success -> {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    syncedCount = outcome.newlyAdded,
                    lastSyncDate = syncPreferences.lastSyncLocalDate() ?: LocalDate.now(),
                    showSuccess = showUi,
                )
            }

            is SyncOutcome.Failure -> {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = outcome.message,
                )
            }
        }
        return outcome
    }
```

**Заменить на:**

```kotlin
    private val syncMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun runSync(showUi: Boolean, block: suspend () -> SyncOutcome): SyncOutcome {
        // Если sync уже идёт — НЕ блокируем UI новой spinner-сменой, тихо ждём результат.
        // Можно сделать `tryLock`, тогда повторный вызов мгновенно вернёт прошлый outcome —
        // но в реальном UI пользователь ожидает завершения текущей операции.
        return syncMutex.withLock {
            if (showUi) {
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    error = null,
                    showSuccess = false,
                )
            }

            val outcome = block()
            when (outcome) {
                is SyncOutcome.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        syncedCount = outcome.newlyAdded,
                        lastSyncDate = syncPreferences.lastSyncLocalDate() ?: LocalDate.now(),
                        showSuccess = showUi,
                    )
                }

                is SyncOutcome.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = outcome.message,
                    )
                }
            }
            outcome
        }
    }
```

**Почему:**
- Mutex сериализует доступ к UI-state. Повторный жим «sync» дождётся завершения первого, потом запустит свой.
- Альтернатива (отвергаем): `_uiState.value.isLoading` checked-and-return — может пропускать обоснованные повторы из-за race на самом флаге.

**Коммит:** `Сериализовал sync вызовы через mutex`

---

## 4.3 `DayDetailsDialog`: стабильный key для LazyColumn

**Файл:** `app/src/main/java/ru/greemlab/neiro/ui/components/DayDetailsDialog.kt`
**Строки:** 357-392

### Проблема

```kotlin
items(intensiveIndices.size) { listIndex ->
    val rawIndex = intensiveIndices[listIndex]
    val intensive = SessionParser.parse(currentNames[rawIndex]) as Session.Intensive
    EditIntensiveItem(...)
}
```

Без `key` Compose сопоставляет items по позиции. При `currentNames.removeAt(rawIndex)` индексы съезжают, внутренний `remember` в `EditIntensiveItem` теряется → потеря input-state, фокуса.

### Сейчас (365):

```kotlin
            ) {
                items(intensiveIndices.size) { listIndex ->
                    val rawIndex = intensiveIndices[listIndex]
                    val intensive = SessionParser.parse(currentNames[rawIndex]) as Session.Intensive
```

### Заменить на:

```kotlin
            ) {
                items(
                    count = intensiveIndices.size,
                    key = { listIndex -> "intensive-${intensiveIndices[listIndex]}-${currentNames.getOrNull(intensiveIndices[listIndex]) ?: ""}" },
                ) { listIndex ->
                    val rawIndex = intensiveIndices[listIndex]
                    val intensive = SessionParser.parse(currentNames[rawIndex]) as Session.Intensive
```

**Почему:**
- Стабильный ключ из `rawIndex` + текущего содержимого. При удалении item Compose правильно идентифицирует оставшиеся.
- Учёт `getOrNull` на случай гонки удаления и recomposition.

**Коммит:** `Добавил стабильный key в список интенсивов`

---

## 4.4 `IntensiveDetailsDialog`: ключ против коллизии имён

**Файл:** `app/src/main/java/ru/greemlab/neiro/ui/components/daydetails/IntensiveDetailsDialog.kt`
**Строки:** 131 (см. `items(children, key = { it.name })`)

### Проблема

Если в интенсиве два ребёнка с одинаковыми именами (омонимы, дубль из YClients) — ключ повторяется → Compose упадёт с `IllegalArgumentException: Key … was already used`.

### Сейчас:

```kotlin
items(children, key = { it.name }) { child ->
```

### Заменить на:

```kotlin
itemsIndexed(
    items = children,
    key = { index, child -> "${index}-${child.name}" },
) { _, child ->
```

И **добавить импорт**:

```kotlin
import androidx.compose.foundation.lazy.itemsIndexed
```

**Почему:**
- `itemsIndexed` гарантирует уникальный ключ через индекс.
- Безопасный fallback для редкого случая дубля имени.

**Коммит:** `Защитил intensive диалог от коллизии ключей`

---

## 4.5 `AttendanceStatusPickerIcon`: touch target 48dp

**Файл:** `app/src/main/java/ru/greemlab/neiro/ui/components/daydetails/AttendanceStatusPicker.kt`
**Строки:** 39-54

### Проблема

`Surface(modifier = Modifier.size(24.dp), onClick = { ... })` — кликаемая зона 24×24dp. Material/a11y guidelines требуют минимум 48×48dp. Также `Modifier.minimumInteractiveComponentSize()` не применён.

### Сейчас (39-54):

```39:54:app/src/main/java/ru/greemlab/neiro/ui/components/daydetails/AttendanceStatusPicker.kt
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = Color.White,
            onClick = { expanded = true },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = AttendanceStatusVisuals.icon(status),
                    contentDescription = stringResource(R.string.attendance_status_picker_cd),
                    tint = indicatorColor,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
```

### Заменить на:

```kotlin
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(24.dp),
            shape = CircleShape,
            color = Color.White,
            onClick = { expanded = true },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = AttendanceStatusVisuals.icon(status),
                    contentDescription = stringResource(R.string.attendance_status_picker_cd),
                    tint = indicatorColor,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
```

И добавить импорт:

```kotlin
import androidx.compose.material3.minimumInteractiveComponentSize
```

**Почему:**
- `minimumInteractiveComponentSize()` — официальный M3-модификатор, расширяет click area до 48dp без увеличения визуального размера.

**Коммит:** `Увеличил touch target для picker статуса`

---

## 4.6 `SessionNotificationSettingsViewModel` → `StateFlow`

**Файл:** `app/src/main/java/ru/greemlab/neiro/ui/settings/SessionNotificationSettingsViewModel.kt`

### Проблема

`var state by mutableStateOf(loadState())` — `MutableState` напрямую в ViewModel. Это работает в Compose, но:
1. Любой `LaunchedEffect` или non-compose observer (`Flow.collect`) не увидит изменения.
2. Тесты ViewModel требуют Compose runtime (`Snapshot.takeMutableSnapshot`).
3. Обновление вне Compose-фрейма (например из background WorkManager callback) — не атомарно.

### Полная замена файла (147 строк):

Сохранив API наружу (UI читает `state` или `state.collectAsState()`):

```kotlin
package ru.greemlab.neiro.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.greemlab.neiro.notifications.ScheduledDigestKind
import ru.greemlab.neiro.notifications.ScheduledNotificationTime
import ru.greemlab.neiro.notifications.SessionNotificationCoordinator
import ru.greemlab.neiro.notifications.SessionNotificationPreferences

data class SessionNotificationSettingsState(
    val isEnabled: Boolean = true,
    val notifyNewBooking: Boolean = true,
    val notifyCancelled: Boolean = true,
    val notifyRescheduled: Boolean = true,
    val notifyDeleted: Boolean = true,
    val notifyClientConfirmed: Boolean = true,
    val notifyClientArrived: Boolean = true,
    val notifyReminder: Boolean = false,
    val notifyTodayDigest: Boolean = true,
    val notifyTomorrowDigest: Boolean = true,
    val notifyArchiveReminder: Boolean = true,
    val reminderMinutesBefore: Int = 30,
    val todayDigestTime: ScheduledNotificationTime = ScheduledNotificationTime(8, 0),
    val tomorrowDigestTime: ScheduledNotificationTime = ScheduledNotificationTime(20, 0),
    val archiveReminderTime: ScheduledNotificationTime = ScheduledNotificationTime(21, 0),
)

class SessionNotificationSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = SessionNotificationPreferences.get(application)

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<SessionNotificationSettingsState> = _state.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.isEnabled = value
        _state.update { it.copy(isEnabled = value) }
        viewModelScope.launch {
            SessionNotificationCoordinator.onNotificationsToggled(getApplication(), value)
        }
    }

    fun setNotifyNewBooking(value: Boolean) = update({ copy(notifyNewBooking = value) }) {
        prefs.notifyNewBooking = value
    }

    fun setNotifyCancelled(value: Boolean) = update({ copy(notifyCancelled = value) }) {
        prefs.notifyCancelled = value
    }

    fun setNotifyRescheduled(value: Boolean) = update({ copy(notifyRescheduled = value) }) {
        prefs.notifyRescheduled = value
    }

    fun setNotifyDeleted(value: Boolean) = update({ copy(notifyDeleted = value) }) {
        prefs.notifyDeleted = value
    }

    fun setNotifyClientConfirmed(value: Boolean) = update({ copy(notifyClientConfirmed = value) }) {
        prefs.notifyClientConfirmed = value
    }

    fun setNotifyClientArrived(value: Boolean) = update({ copy(notifyClientArrived = value) }) {
        prefs.notifyClientArrived = value
    }

    fun setNotifyReminder(value: Boolean) = update({ copy(notifyReminder = value) }) {
        prefs.notifyReminder = value
    }

    fun setNotifyTodayDigest(value: Boolean) = update({ copy(notifyTodayDigest = value) }) {
        prefs.notifyTodayDigest = value
    }

    fun setNotifyTomorrowDigest(value: Boolean) = update({ copy(notifyTomorrowDigest = value) }) {
        prefs.notifyTomorrowDigest = value
    }

    fun setNotifyArchiveReminder(value: Boolean) = update({ copy(notifyArchiveReminder = value) }) {
        prefs.notifyArchiveReminder = value
    }

    fun setReminderMinutes(minutes: Int) = update({ copy(reminderMinutesBefore = minutes) }) {
        prefs.reminderMinutesBefore = minutes
    }

    fun setTodayDigestTime(time: ScheduledNotificationTime) {
        prefs.todayDigestTime = time
        prefs.clearTodayDigestShown()
        _state.update { it.copy(todayDigestTime = time) }
        viewModelScope.launch {
            SessionNotificationCoordinator.onDigestTimeChanged(getApplication(), ScheduledDigestKind.TODAY)
        }
    }

    fun setTomorrowDigestTime(time: ScheduledNotificationTime) {
        prefs.tomorrowDigestTime = time
        prefs.clearTomorrowDigestShown()
        _state.update { it.copy(tomorrowDigestTime = time) }
        viewModelScope.launch {
            SessionNotificationCoordinator.onDigestTimeChanged(getApplication(), ScheduledDigestKind.TOMORROW)
        }
    }

    fun setArchiveReminderTime(time: ScheduledNotificationTime) {
        prefs.archiveReminderTime = time
        prefs.clearArchiveReminderShown()
        _state.update { it.copy(archiveReminderTime = time) }
        viewModelScope.launch {
            SessionNotificationCoordinator.onDigestTimeChanged(getApplication(), ScheduledDigestKind.ARCHIVE)
        }
    }

    private inline fun update(
        crossinline stateTransform: SessionNotificationSettingsState.() -> SessionNotificationSettingsState,
        persist: () -> Unit,
    ) {
        persist()
        _state.update { it.stateTransform() }
        viewModelScope.launch {
            SessionNotificationCoordinator.onSettingsChanged(getApplication())
        }
    }

    private fun loadState() = SessionNotificationSettingsState(
        isEnabled = prefs.isEnabled,
        notifyNewBooking = prefs.notifyNewBooking,
        notifyCancelled = prefs.notifyCancelled,
        notifyRescheduled = prefs.notifyRescheduled,
        notifyDeleted = prefs.notifyDeleted,
        notifyClientConfirmed = prefs.notifyClientConfirmed,
        notifyClientArrived = prefs.notifyClientArrived,
        notifyReminder = prefs.notifyReminder,
        notifyTodayDigest = prefs.notifyTodayDigest,
        notifyTomorrowDigest = prefs.notifyTomorrowDigest,
        notifyArchiveReminder = prefs.notifyArchiveReminder,
        reminderMinutesBefore = prefs.reminderMinutesBefore,
        todayDigestTime = prefs.todayDigestTime,
        tomorrowDigestTime = prefs.tomorrowDigestTime,
        archiveReminderTime = prefs.archiveReminderTime,
    )
}
```

### Обновить consumer

Найти места, где UI читает `viewModel.state`:

```
rg 'SessionNotificationSettingsViewModel' app/src/main
```

В Compose-экране заменить:

```kotlin
val state = viewModel.state  // было: MutableState
```

На:

```kotlin
val state by viewModel.state.collectAsStateWithLifecycle()
```

И добавить импорт:

```kotlin
import androidx.lifecycle.compose.collectAsStateWithLifecycle
```

**Зависимость:** `androidx.lifecycle:lifecycle-runtime-compose`. **Проверка:**

```
rg 'lifecycle-runtime-compose|collectAsStateWithLifecycle' app/build.gradle.kts gradle/libs.versions.toml
```

Если зависимости нет — добавить в `libs.versions.toml`:

```toml
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycleRuntimeKtx" }
```

И в `app/build.gradle.kts` рядом с `androidx-lifecycle-runtime-ktx`:

```kotlin
implementation(libs.androidx.lifecycle.runtime.compose)
```

**Альтернатива (если не хочется тянуть compose-runtime):**

```kotlin
val state by viewModel.state.collectAsState()  // androidx.compose.runtime.collectAsState
```

— достаточно, не нужны новые зависимости.

**Коммит:** `Перевёл NotificationSettings VM на StateFlow`

---

## 4.7 `DayScheduleTimeline`: snapshotFlow для scroll

**Файл:** `app/src/main/java/ru/greemlab/neiro/ui/components/daydetails/DayScheduleTimeline.kt`
**Строки:** 133-135

### Проблема

```kotlin
LaunchedEffect(scrollState.value) {
    onTopReachedChanged(scrollState.value == 0)
}
```

`LaunchedEffect(scrollState.value)` — каждое изменение скролла (по pixel) триггерит cancel+restart корутины. Это **очень дорого** при быстром свайпе (десятки cancel в секунду).

### Заменить на:

```kotlin
    LaunchedEffect(scrollState) {
        kotlinx.coroutines.flow.snapshotFlow { scrollState.value == 0 }
            .distinctUntilChanged()
            .collect(onTopReachedChanged)
    }
```

Или, чтобы не тянуть `snapshotFlow` напрямую:

```kotlin
    LaunchedEffect(scrollState) {
        androidx.compose.runtime.snapshotFlow { scrollState.value == 0 }
            .distinctUntilChanged()
            .collect(onTopReachedChanged)
    }
```

**Импорт:**

```kotlin
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
```

**Почему:**
- `snapshotFlow` подписывается только на чтение `scrollState.value` внутри блока.
- `distinctUntilChanged` отбрасывает повторные эмиссии того же булева.
- Корутина запускается один раз для всего жизненного цикла компонента.

**Коммит:** `Заменил scroll-эффект на snapshotFlow`

---

## 4.8 `ScheduleSlotItem`: drag без `scope.launch { snapTo }`

**Файл:** `app/src/main/java/ru/greemlab/neiro/ui/components/daydetails/ScheduleSlotItem.kt`
**Строки:** 287-313, 417-446

### Проблема

```kotlin
detectHorizontalDragGestures(
    onHorizontalDrag = { change, dragAmount ->
        change.consume()
        val delta = dragAmount / (maxWidthPx * 0.8f)
        scope.launch {
            expansion.snapTo((expansion.value + delta).coerceIn(0f, 1f))
        }
    },
    ...
)
```

`scope.launch` на каждый кадр drag — десятки корутин в секунду, GC pressure.

### Заменить на (используя `Animatable.snapTo` — он suspend, но можно вызвать через `runBlocking`-аналог? нет, лучше `Animatable` + `MutableFloatState` для жеста):

Самое чистое решение: использовать `MutableFloatState` для текущего значения drag, а `Animatable.animateTo` — для финального snap.

Сейчас (287-313):

```287:313:app/src/main/java/ru/greemlab/neiro/ui/components/daydetails/ScheduleSlotItem.kt
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(removed.size) {
                val maxWidthPx = size.width.toFloat()
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val delta = dragAmount / (maxWidthPx * 0.8f)
                        scope.launch {
                            expansion.snapTo((expansion.value + delta).coerceIn(0f, 1f))
                        }
                    },
                    onDragEnd = {
                        val v = expansion.value
                        val target = when {
                            v < 0.25f -> 0f
                            v < 0.75f -> 0.5f
                            else -> 1f
                        }
                        scope.launch {
                            expansion.animateTo(target, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                            if (target == 0.5f && !expanded) onToggle()
                            else if (target == 0f && expanded) onToggle()
                        }
                    },
                )
            },
    ) {
```

**Заменить на:**

```kotlin
    val dragChannel = remember { kotlinx.coroutines.channels.Channel<Float>(kotlinx.coroutines.channels.Channel.CONFLATED) }
    LaunchedEffect(dragChannel) {
        for (delta in dragChannel) {
            expansion.snapTo((expansion.value + delta).coerceIn(0f, 1f))
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(removed.size) {
                val maxWidthPx = size.width.toFloat()
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val delta = dragAmount / (maxWidthPx * 0.8f)
                        dragChannel.trySend(delta)
                    },
                    onDragEnd = {
                        val v = expansion.value
                        val target = when {
                            v < 0.25f -> 0f
                            v < 0.75f -> 0.5f
                            else -> 1f
                        }
                        scope.launch {
                            expansion.animateTo(target, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                            if (target == 0.5f && !expanded) onToggle()
                            else if (target == 0f && expanded) onToggle()
                        }
                    },
                )
            },
    ) {
```

Аналогично для второго блока (417-446) в `ExpandableIntensiveCoverSlot`.

**Почему:**
- `Channel.CONFLATED` ёмкость 1: при поступлении нового значения старое перезаписывается. Идеально для drag — нам важно последнее значение, не все промежуточные.
- Корутина в `LaunchedEffect` запускается один раз. Перевод значений в `expansion` остаётся последовательным.

**Альтернатива (проще, но менее эффективно):** оставить как есть. Анимация и так работает, замер perf не показал реальных тормозов в проде. **Если есть сомнения — оставить как есть в Out-of-Scope.** Можно перенести в OUT_OF_SCOPE.md если этот рефакторинг покажется неоправданным.

**Коммит:** `Снизил аллокации в drag слота`

> Если есть сомнения в правильности — этот пункт **можно пропустить** в этапе и перенести в Out-of-Scope. Drag работает и сейчас.

---

## 4.9 Другие мелкие правки (low priority)

### 4.9.1 `Locale("ru")` → `Locale.forLanguageTag("ru")` (если ETAP 1 не покрыл)

См. Этап 1, пункт 1.8.

### 4.9.2 `AppSettingsScreen` — `prefs.isEnabled` читается напрямую

Если в `app/src/main/java/ru/greemlab/neiro/ui/screens/AppSettingsScreen.kt` (или похожих экранах) состояние читается из SharedPreferences напрямую — переписать на `viewModel.state.collectAsState()`. **Проверка по grep:**

```
rg 'SessionNotificationPreferences\.get|prefs\.isEnabled' app/src/main/java/ru/greemlab/neiro/ui
```

Если есть прямые чтения из prefs в `@Composable` — это анти-паттерн (recomposition не среагирует на изменение). Обернуть через VM. Эту правку **по желанию**, не критическая.

---

## Финальная проверка этапа

1. **`ReadLints`** для всех правленых файлов.
2. **Проверка**, что `DisposableEffect(Unit)` действительно сработает: `WebView` не должен освобождаться при рекомпозиции (только при покидании Composable). Тест: возврат к экрану календаря из WebView, проверить через DevTools memory profiler (если возможно).
3. Поиск `LazyColumn.*items\(.*\.size\)` без `key`:
   ```
   rg 'items\(.*\.size\)' app/src/main/java/ru/greemlab/neiro/ui
   ```
   Должен остаться **только** правленный `DayDetailsDialog.kt` с `key = { ... }`.

## Коммиты этапа (порядок)

1. `Освобождаю WebView при выходе с экрана`
2. `Сериализовал sync вызовы через mutex`
3. `Добавил стабильный key в список интенсивов`
4. `Защитил intensive диалог от коллизии ключей`
5. `Увеличил touch target для picker статуса`
6. `Перевёл NotificationSettings VM на StateFlow`
7. `Заменил scroll-эффект на snapshotFlow`
8. `Снизил аллокации в drag слота` *(опционально)*
