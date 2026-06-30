# Этап 6 — Core (Application, MainActivity)

**Уровень риска:** Низкий. Правки ядра приложения. После выполнения проще всего проверить cold-start и deep-link из push.
**Зависимости:** Этап 1 (backup rules) — для `data_extraction_rules.xml`. Этап 2 (`LogoutCoordinator`) — для logout flow на уровне приложения.
**Acceptance:**
- [ ] `NeiroApplication.onCreate` не блокирует main thread — все coordinator init вызовы либо безопасны (synchronous), либо обёрнуты в `appScope.launch`.
- [ ] `MainActivity` не дублирует `enableEdgeToEdge` + `applyWindowSystemBars` — оставлен один механизм.
- [ ] Deep-link state (`openDate`, `highlightSlotKey`, `notificationDeepLinkVersion`) переживает rotation (`rememberSaveable` или сохранение в `savedInstanceState`).
- [ ] `RequestNotificationPermissionIfNeeded` показывает rationale, если пользователь ранее отклонил permission.
- [ ] `applyNotificationExtras` идемпотентен (не инкрементит `notificationDeepLinkVersion` если extras не изменились).

---

## Файлы для правки

1. `app/src/main/java/ru/greemlab/neiro/NeiroApplication.kt`
2. `app/src/main/java/ru/greemlab/neiro/MainActivity.kt`
3. `app/src/main/java/ru/greemlab/neiro/theme/SystemBars.kt`
4. (опц.) `app/src/main/AndroidManifest.xml` — `android:configChanges`

---

## 6.1 `NeiroApplication.onCreate` — async init

**Файл:** `app/src/main/java/ru/greemlab/neiro/NeiroApplication.kt`

### Проблема

`SessionNotificationCoordinator.initialize` синхронно `rescheduleAllWork(appContext)` → каждый из 3 `WorkManager.enqueueUniquePeriodicWork()` делает disk I/O. На холодном старте — суммарно до 200ms на main thread.

`PushRegistrar.initialize` асинхронен сам (`scope.launch`), `AutoSyncCoordinator.initialize` и `LiveApiCoordinator.initialize` — синхронны (просто `ProcessLifecycleOwner.addObserver` и `scope.launch`-инициализация).

Цель — переместить весь init в `appScope.launch`, кроме того что **обязательно** должно быть до первого Activity (ничего, на самом деле).

### Сейчас (19-37):

```19:37:app/src/main/java/ru/greemlab/neiro/NeiroApplication.kt
    override fun onCreate() {
        super.onCreate()

        AutoSyncCoordinator.initialize(this)
        LiveApiCoordinator.initialize(this)
        SessionNotificationCoordinator.initialize(this)
        PushRegistrar.initialize(this)

        // Синхронный SharedPreferences-кэш заполняет снимок прямо в конструкторе репозитория,
        // поэтому UI стартует с данными без блокировки main-потока.
        val repository = CalendarDataStoreProvider.get(this)

        // Фоновая гидратация из DataStore + миграции — параллельно со стартом UI.
        appScope.launch {
            repository.warmUp()
            repository.migrateProfileIfNeeded()
            SessionNotificationCoordinator.refreshFromCalendar(this@NeiroApplication)
        }
    }
```

### Заменить на:

```kotlin
    override fun onCreate() {
        super.onCreate()

        // Синхронный SharedPreferences-кэш заполняет снимок прямо в конструкторе репозитория,
        // поэтому UI стартует с данными без блокировки main-потока.
        val repository = CalendarDataStoreProvider.get(this)

        // ProcessLifecycleOwner.addObserver обязан быть на main thread — оставляем здесь.
        AutoSyncCoordinator.initialize(this)
        LiveApiCoordinator.initialize(this)

        appScope.launch {
            // Эти init делают disk I/O (WorkManager.enqueue, FCM token) — не блокируем main.
            SessionNotificationCoordinator.initialize(this@NeiroApplication)
            PushRegistrar.initialize(this@NeiroApplication)

            repository.warmUp()
            repository.migrateProfileIfNeeded()
            SessionNotificationCoordinator.refreshFromCalendar(this@NeiroApplication)
        }
    }
```

**Почему:**
- `AutoSyncCoordinator.initialize` и `LiveApiCoordinator.initialize` вызывают `ProcessLifecycleOwner.get().lifecycle.addObserver(...)` — этот API требует main thread.
- `SessionNotificationCoordinator.initialize` — disk I/O (channel + enqueue) → переносим в background.
- `PushRegistrar.initialize` — асинхронен сам, но обёртка в `appScope.launch` гарантирует, что `if (!PushConfig.isActive) return` отрабатывает не на main.

**Альтернатива:** проверить, можно ли `addObserver` сделать lazy. Нет — нужно сразу, чтобы `onStart` ловить.

**Коммит:** `Перенёс тяжёлый init в Application scope`

---

## 6.2 `MainActivity`: убрать дублирование edge-to-edge

**Файл:** `app/src/main/java/ru/greemlab/neiro/MainActivity.kt`
**Строки:** 56-59, 149

### Проблема

В `onCreate` вызывается `enableEdgeToEdge()` (строка 56), а в `setContent { ... ApplySystemBars(...) }` (строка 149) ещё раз `applyWindowSystemBars(window, ...)`. Оба пишут в `window.statusBarColor`/`navigationBarColor` и `setDecorFitsSystemWindows(false)`. Это работает, но при смене темы (`ApplySystemBars` пересчитывает на каждом recomposition) могут случаться мерцания.

### Решение

`enableEdgeToEdge()` — официальный AndroidX-механизм для transparent bars. `ApplySystemBars` нужен только для управления `isAppearanceLightStatusBars` (свет/тёмный режим иконок). Оставляем `enableEdgeToEdge`, упрощаем `applyWindowSystemBars`.

### Правка `theme/SystemBars.kt` (15-29):

```15:29:app/src/main/java/ru/greemlab/neiro/theme/SystemBars.kt
internal fun applyWindowSystemBars(
    window: Window,
    view: View,
    backgroundColor: Color,
    darkTheme: Boolean,
) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = Color.Transparent.toArgb()
    window.navigationBarColor = Color.Transparent.toArgb()
    window.setBackgroundDrawable(ColorDrawable(backgroundColor.toArgb()))
    WindowCompat.getInsetsController(window, view).apply {
        isAppearanceLightStatusBars = !darkTheme
        isAppearanceLightNavigationBars = !darkTheme
    }
}
```

**Заменить на:**

```kotlin
internal fun applyWindowSystemBars(
    window: Window,
    view: View,
    backgroundColor: Color,
    darkTheme: Boolean,
) {
    // enableEdgeToEdge() в MainActivity уже выставил setDecorFitsSystemWindows(false)
    // и сделал статус/навбары прозрачными — здесь только подстраиваем иконки и цвет фона окна.
    window.setBackgroundDrawable(ColorDrawable(backgroundColor.toArgb()))
    WindowCompat.getInsetsController(window, view).apply {
        isAppearanceLightStatusBars = !darkTheme
        isAppearanceLightNavigationBars = !darkTheme
    }
}
```

**Почему:** Меньше mutating window-state на каждый recomposition темы.

**Коммит:** `Убрал дубль edge-to-edge`

---

## 6.3 Deep-link state: `rememberSaveable` против rotation

**Файл:** `app/src/main/java/ru/greemlab/neiro/MainActivity.kt`
**Строки:** 50-52

### Проблема

`openDate`, `highlightSlotKey`, `notificationDeepLinkVersion` хранятся в `mutableStateOf` на уровне Activity. При rotation Activity пересоздаётся, состояние теряется → если пользователь крутанул телефон во время transition из push, deep-link не доедет.

### Решение

В `Activity`-классе нет прямого `rememberSaveable` — нужно сохранять через `savedInstanceState`.

### Сейчас:

```50:52:app/src/main/java/ru/greemlab/neiro/MainActivity.kt
    private var openDate by mutableStateOf<String?>(null)
    private var highlightSlotKey by mutableStateOf<String?>(null)
    private var notificationDeepLinkVersion by mutableIntStateOf(0)
```

### Заменить (полная замена `onCreate` + add `onSaveInstanceState`):

```kotlin
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
```

И добавить в companion (45-48):

```kotlin
    companion object {
        const val EXTRA_OPEN_DATE = "open_date"
        const val EXTRA_HIGHLIGHT_SLOT_KEY = "highlight_slot_key"
        private const val STATE_OPEN_DATE = "neiro.state.open_date"
        private const val STATE_HIGHLIGHT_SLOT_KEY = "neiro.state.highlight_slot_key"
        private const val STATE_DEEP_LINK_VERSION = "neiro.state.deep_link_version"
    }
```

**Почему:**
- При rotation `savedInstanceState != null` → переиспользуем сохранённое состояние, не сбрасываем `applyNotificationExtras` (иначе `notificationDeepLinkVersion++` снова бы триггернул deep-link).
- При cold-start `savedInstanceState == null` → читаем `intent.getStringExtra(EXTRA_OPEN_DATE)` как раньше.

**Коммит:** `Сохраняю deep-link state при пересоздании Activity`

---

## 6.4 `applyNotificationExtras` идемпотентен

**Файл:** `app/src/main/java/ru/greemlab/neiro/MainActivity.kt`
**Строки:** 81-85

### Проблема

```kotlin
private fun applyNotificationExtras(source: Intent?) {
    openDate = source?.getStringExtra(EXTRA_OPEN_DATE)
    highlightSlotKey = source?.getStringExtra(EXTRA_HIGHLIGHT_SLOT_KEY)
    notificationDeepLinkVersion++
}
```

`onNewIntent` вызывается даже когда пришёл intent с теми же extras (вторая FCM с тем же payload). `notificationDeepLinkVersion++` каждый раз → лишняя ре-навигация в Compose, мигание UI.

### Заменить:

```kotlin
    private fun applyNotificationExtras(source: Intent?) {
        val newOpenDate = source?.getStringExtra(EXTRA_OPEN_DATE)
        val newHighlight = source?.getStringExtra(EXTRA_HIGHLIGHT_SLOT_KEY)
        val changed = newOpenDate != openDate || newHighlight != highlightSlotKey
        openDate = newOpenDate
        highlightSlotKey = newHighlight
        if (changed) notificationDeepLinkVersion++
    }
```

**Коммит:** `Сделал applyNotificationExtras идемпотентным`

---

## 6.5 `RequestNotificationPermissionIfNeeded`: rationale

**Файл:** `app/src/main/java/ru/greemlab/neiro/MainActivity.kt`
**Строки:** 88-121

### Проблема

При первом запуске диалог permission показывается **сразу**, без объяснения зачем оно нужно. Если пользователь нажал «Запретить» — больше не показывается никогда (Android запоминает выбор). А ведь это критичное permission — без него ВСЕ push не доходят.

### Решение

После первого отклонения — показывать rationale (объясняющий диалог). Можно сделать минимально — через `shouldShowRequestPermissionRationale` + Material AlertDialog. Но это меняет UI, лучше доверить отдельной задаче по UX.

**Минимальная правка**: запрашивать **один раз** при первом запуске, не ловить разрешение в `LaunchedEffect(granted)` (который перезапускается при rotation).

### Сейчас (114-120):

```114:120:app/src/main/java/ru/greemlab/neiro/MainActivity.kt
    LaunchedEffect(granted) {
        if (!granted) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            SessionNotificationCoordinator.checkDueDigestsOnAppOpen(appContext)
        }
    }
```

**Проблема:** `LaunchedEffect(granted)` каждый раз когда `granted == false` и Compose recomposит → перезапуск `launcher.launch`. Но `ActivityResultContracts.RequestPermission` сам не покажет диалог второй раз, если уже отклонено — поэтому это **не критично**. Но мы не получаем callback `onResult` (он уже отработал в первый раз и не вернётся).

### Заменить на:

```kotlin
    LaunchedEffect(Unit) {
        if (!granted) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            SessionNotificationCoordinator.checkDueDigestsOnAppOpen(appContext)
        }
    }
```

**Почему:** `LaunchedEffect(Unit)` запускается один раз на жизненный цикл Composable, не реагирует на изменение `granted` (которое всё равно требует cold-restart Activity, т.к. permission state читается только в `ContextCompat.checkSelfPermission`).

**Альтернатива (минимально, без rationale):** оставить как есть. Это **не критично**.

**Опционально (расширенный rationale):** добавить state, отслеживать `shouldShowRequestPermissionRationale`. Это **отдельная UX-задача**, см. OUT_OF_SCOPE.

**Коммит:** `Запрашиваю permission один раз за сессию`

---

## 6.6 *(Опционально)* `configChanges` для `MainActivity`

**Файл:** `app/src/main/AndroidManifest.xml`

### Проблема

При повороте, изменении locale, dark/light mode — `Activity` пересоздаётся. С `rememberSaveable`/`savedInstanceState` (пункт 6.3) состояние не теряется, но recreation всё равно дорогое: пересоздание DataStore-flow, повторный warmUp UI, мигание SplashScreen.

### Решение

Добавить `android:configChanges="orientation|screenSize|smallestScreenSize|keyboardHidden|locale|layoutDirection|uiMode|fontScale|density"`:

В `AndroidManifest.xml:19-30` найти `<activity android:name=".MainActivity"`, заменить на:

```xml
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:label="@string/app_name"
            android:theme="@style/Theme.Neiro"
            android:configChanges="orientation|screenSize|smallestScreenSize|keyboardHidden|locale|layoutDirection|uiMode|fontScale|density">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
```

**Почему:**
- Compose сам реагирует на эти изменения через `LocalConfiguration` — пересоздание Activity не нужно.
- Включить `uiMode` — переключатель day/night не пересоздаст Activity, тема перестроится плавно.

**Тестировать:** поворот, переключение dark mode в системе.

> **Внимание:** если в проекте используются ресурсы, специфичные для конфигурации (например `values-night/strings.xml` с разным содержимым), при `configChanges` они **не перезагружаются** автоматически. **В нашем проекте такого нет**, поэтому безопасно. Но если в будущем добавится `values-land/` или `values-ru-rRU/strings.xml` — `configChanges` нужно будет частично откатить.

**Коммит:** `Не пересоздаю Activity при простых конфиг-изменениях`

---

## 6.7 *(Низкий приоритет)* `MainActivity` использует `Surface` вместо `Box(background)`

**Файл:** `app/src/main/java/ru/greemlab/neiro/MainActivity.kt`
**Строки:** 148-162

### Сейчас

```kotlin
    NeiroTheme(darkTheme = isDarkTheme) {
        ApplySystemBars(darkTheme = isDarkTheme)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            CalendarScreen(...)
        }
    }
```

`Box(background)` работает, но `Surface` или `Scaffold` — более идиоматичный M3-подход.

### Замена (опционально):

```kotlin
    NeiroTheme(darkTheme = isDarkTheme) {
        ApplySystemBars(darkTheme = isDarkTheme)
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            CalendarScreen(...)
        }
    }
```

Импорт: `import androidx.compose.material3.Surface`.

**Почему:** Косметика; на проде ничего не меняется. **Этот пункт можно пропустить.**

**Коммит:** `Перешёл на Surface для корневого контейнера` *(опционально)*

---

## Финальная проверка этапа

1. **`ReadLints`** для всех правленых файлов.
2. **Тест rotation** — повернуть устройство на CalendarScreen после открытия диалога дня. Состояние диалога не должно сбрасываться (`rememberSaveable` внутри `DayDetailsDialog` — отдельная история; здесь — проверяем что Activity не сбрасывает deep-link).
3. **Тест cold start** — закрыть приложение через recents, открыть через push — должен открыться правильный день, без двух recomposition.

## Коммиты этапа (порядок)

1. `Перенёс тяжёлый init в Application scope`
2. `Убрал дубль edge-to-edge`
3. `Сохраняю deep-link state при пересоздании Activity`
4. `Сделал applyNotificationExtras идемпотентным`
5. `Запрашиваю permission один раз за сессию`
6. `Не пересоздаю Activity при простых конфиг-изменениях` *(опционально)*
7. `Перешёл на Surface для корневого контейнера` *(опционально)*
