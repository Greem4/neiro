# Самообновление: архитектура

Как приложение узнаёт о новой версии, качает её и ставит поверх себя.
Документ проектный: кода ещё нет, фрагменты ниже — эталон того, что должно
получиться, вплоть до имён классов и файлов.

Релизная сторона (кто и как кладёт APK в GitHub) — в [RELEASE.md](RELEASE.md).
Ограничения платформы, из-за которых часть решений выглядит странно, —
в [RISKS.md](RISKS.md).

## Общая картина

```
                     ┌──────────────────────────────────────┐
                     │ GitHub Releases (Greem4/neiro)       │
                     │   tag v0.2.0                         │
                     │   neiro-0.2.0.apk                    │
                     │   SHA256SUMS.txt                     │
                     └──────────────────────────────────────┘
                                    ▲            │
                        1. что там? │            │ 3. качаем APK
                           GET /releases/latest  │    + SHA256SUMS.txt
                                    │            ▼
┌───────────────────────────────────┴─────────────────────────────────┐
│ Приложение                                                          │
│                                                                     │
│  UpdateCheckWorker ──► UpdateChecker ──► UpdateStatus.Available     │
│    (раз в сутки)            │                     │                 │
│                             │                     ▼                 │
│                             │            UpdateNotifier             │
│                             │        системное уведомление          │
│                             │                     │                 │
│                             │        2. пользователь согласился     │
│                             ▼                     ▼                 │
│                       UpdateDownloader ──► UpdateVerifier           │
│                       cacheDir/updates/    SHA256 + подпись         │
│                                                   │                 │
│                                    4. только если сошлось           │
│                                                   ▼                 │
│                                            ApkInstaller             │
│                                       PackageInstaller Session      │
│                                                   │                 │
│                                                   ▼                 │
│                                    UpdateInstallReceiver            │
│                              SUCCESS │ PENDING_USER_ACTION │ FAILURE│
└─────────────────────────────────────────────────────────────────────┘
```

Порядок шагов важен: **проверка целостности идёт до установки**, а не после.
Система сама откажется ставить APK с чужой подписью, но сообщение будет
невнятное («Приложение не установлено»), и пользователь останется с
непонятной ошибкой. Проверяем сами — и говорим человеческим языком.

## Пакет `ru.greemlab.neiro.update`

Всё новое живёт в одном пакете. Ни один существующий файл не переезжает.

```
app/src/main/java/ru/greemlab/neiro/update/
  UpdateConfig.kt          — константы: репозиторий, интервалы, включённость
  UpdateChannelGate.kt     — можно ли этой сборке обновлять себя
  GithubApi.kt             — Retrofit-интерфейс GitHub Releases
  GithubModels.kt          — GithubRelease, GithubAsset
  UpdateClient.kt          — OkHttp + Retrofit, по образцу PushClient
  ReleaseVersion.kt        — разбор тега, versionCode, сравнение  ← чистая логика
  UpdateChecker.kt         — «есть ли новее» → UpdateStatus
  UpdatePreferences.kt     — SharedPreferences, по образцу SyncPreferences
  UpdateDownloader.kt      — скачивание APK с прогрессом
  UpdateVerifier.kt        — SHA256 и сверка подписи
  ApkInstaller.kt          — PackageInstaller Session API
  UpdateInstallReceiver.kt — результат установки
  UpdateNotifier.kt        — системное уведомление о новой версии
  UpdateCheckWorker.kt     — суточная проверка
  UpdateCheckCoordinator.kt— планирование работы, вызов при старте
  UpdateState.kt           — состояния для UI
  UpdateViewModel.kt       — состояние экрана «О программе»

app/src/main/java/ru/greemlab/neiro/ui/settings/
  AboutScreen.kt           — экран «О программе» с кнопкой обновления
```

Тесты — `app/src/test/java/ru/greemlab/neiro/update/`: `ReleaseVersionTest`,
`UpdateCheckerTest`, `Sha256SumsParserTest`, `AssetPickerTest`. Всё, что можно
проверить без устройства, вынесено в чистые функции именно ради этого.

## Источник правды: GitHub Releases

Один запрос, без токена — репозиторий публичный:

```
GET https://api.github.com/repos/Greem4/neiro/releases/latest
Accept: application/vnd.github+json
X-GitHub-Api-Version: 2022-11-28
User-Agent: neiro-android/<versionName>
```

Из ответа нужны пять полей: `tag_name`, `name`, `body` (что изменилось),
`html_url` (ссылка «открыть на GitHub») и `assets[]`. `draft` и `prerelease`
эндпоинт `/latest` и так не отдаёт — но проверить флаги дешевле, чем однажды
раскатать черновик.

**Контракт релиза** (его обязан соблюдать workflow, см. [RELEASE.md](RELEASE.md)):

- ровно один ассет с расширением `.apk`, имя `neiro-<версия>.apk`;
- ассет `SHA256SUMS.txt` со строкой вида `<64 hex>  neiro-<версия>.apk`;
- тег строго `vX.Y.Z`.

Приложение ищет APK по имени `neiro-<версия из тега>.apk`; если не нашло —
берёт единственный `.apk` в списке. Два APK в релизе — ошибка, обновление
отменяется с внятной причиной, а не выбирается наугад.

**Лимит запросов.** Без токена GitHub даёт 60 запросов в час на IP. Мы тратим
один в сутки плюс редкие ручные проверки — запас стократный. Но ответ 403 с
`X-RateLimit-Remaining: 0` всё равно обрабатывается отдельно: это не «сети
нет» и не «обновлений нет», и повторять раньше чем через час бессмысленно.

## Версия и сравнение

Единственное, по чему сравниваются сборки, — `versionCode`. `versionName`
показывается человеку. Формула та же, что в Gradle (см.
[RELEASE.md § Версии](RELEASE.md#версии)):

```kotlin
/**
 * Версия релиза, разобранная из тега вида `v0.2.0`.
 *
 * versionCode считается той же формулой, что и в build.gradle.kts:
 * major * 10000 + minor * 100 + patch. Если формулы разойдутся,
 * приложение начнёт предлагать «обновление» на само себя — поэтому
 * обе стороны проверяются тестами, а CI сверяет тег с version.properties.
 */
data class ReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) {
    val versionCode: Int get() = major * 10_000 + minor * 100 + patch
    val versionName: String get() = "$major.$minor.$patch"

    companion object {
        private val TAG_REGEX = Regex("""^v(\d+)\.(\d+)\.(\d+)$""")

        /** null — тег не нашей схемы; такой релиз молча игнорируем. */
        fun parseTag(tag: String): ReleaseVersion? {
            val m = TAG_REGEX.matchEntire(tag.trim()) ?: return null
            val (major, minor, patch) = m.destructured
            // minor и patch выше 99 сломали бы монотонность versionCode:
            // 0.1.100 и 0.2.0 дали бы одно и то же число.
            val v = ReleaseVersion(major.toInt(), minor.toInt(), patch.toInt())
            return if (v.minor > 99 || v.patch > 99) null else v
        }
    }
}
```

Новее — значит `remote.versionCode > BuildConfig.VERSION_CODE`. Не «tag !=
versionName»: строковое сравнение однажды предложит откатиться на старую
версию, потому что «0.10.0» меньше «0.9.0» по алфавиту.

## Состояния

```kotlin
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState
    data class Verifying(val info: UpdateInfo) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val apk: File) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    /** Система требует подтверждения — ждём, пока пользователь ответит. */
    data class AwaitingConfirmation(val info: UpdateInfo) : UpdateState
    data class Failed(val reason: UpdateFailure, val info: UpdateInfo?) : UpdateState
    /** Сборка из магазина или debug — обновлять себя нельзя. */
    data class Blocked(val why: UpdateBlockReason) : UpdateState
}
```

`UpdateFailure` — перечисление с текстом для пользователя, не голая строка:
`NoNetwork`, `RateLimited`, `NoRelease`, `MalformedRelease`, `DownloadFailed`,
`ChecksumMismatch`, `SignatureMismatch`, `NoSpace`, `InstallRejected`,
`InstallFailed`. Каждая причина ведёт себя по-разному: `NoNetwork` — повторить
позже молча, `ChecksumMismatch` — удалить файл и показать красным, `NoSpace` —
сказать, сколько нужно места.

## Кому вообще можно обновляться

Три случая, когда самообновление обязано молчать:

```kotlin
object UpdateChannelGate {

    /**
     * Debug и prerelease имеют свой applicationId (.debug/.prerelease) —
     * релизный APK для них не обновление, а второе приложение рядом.
     * Флаг приходит из BuildConfig, чтобы проверка была ещё и compile-time.
     */
    fun blockReason(context: Context): UpdateBlockReason? {
        if (!BuildConfig.UPDATE_ENABLED) return UpdateBlockReason.NotReleaseBuild
        return when (installerPackage(context)) {
            // Магазин обновляет сам и подписывает по-своему: наш APK поверх
            // не встанет (см. RISKS.md § Магазин и самообновление).
            RUSTORE_PACKAGE -> UpdateBlockReason.InstalledFromRuStore
            PLAY_PACKAGE -> UpdateBlockReason.InstalledFromPlay
            else -> null
        }
    }
}
```

`installerPackage` — `getInstallSourceInfo().installingPackageName` на API 30+
и `getInstallerPackageName` ниже. `null`, `com.android.shell` (adb),
системный установщик и наш собственный пакет (обновились сами) — разрешены.
Идентификатор RuStore (`ru.vk.store`) проверяется на живом устройстве до того,
как этап считается сделанным: константа из документации без проверки — та же
догадка.

## Проверка: `UpdateChecker`

```kotlin
suspend fun check(force: Boolean = false): UpdateStatus
```

Порядок: гейт канала → троттлинг (если не `force` и прошло меньше суток —
вернуть прошлый результат, сеть не трогать) → запрос → разбор тега → сравнение
с `BuildConfig.VERSION_CODE` → запись метки времени в `UpdatePreferences`.

Метка времени пишется **и при неудаче тоже** — иначе телефон без сети будет
дёргать сеть при каждом запуске приложения.

## Хранилище: `UpdatePreferences`

`SharedPreferences` с именем `neiro_update_prefs`, по образцу
`SyncPreferences` — тот же стиль, тот же `get(context)`-синглтон.

| Ключ | Смысл |
|---|---|
| `auto_check_enabled` | Проверять автоматически (по умолчанию `true`) |
| `last_check_epoch` | Когда последний раз спрашивали GitHub — успешно или нет |
| `last_known_version_code` | Что видели в прошлый раз (кэш для офлайна) |
| `notified_version_code` | О какой версии уже уведомляли — чтобы не звонить каждый день об одном и том же |
| `skipped_version_code` | «Пропустить эту версию» — молчим, пока не выйдет следующая |
| `pending_apk_path` / `pending_version_code` | Скачанный, но не установленный APK |
| `updated_from_version_code` | Ставилось обновление; после старта показываем «Обновлено до 0.2.0» и чистим |

## Скачивание и проверка

Файл кладём в `context.cacheDir/updates/neiro-<версия>.apk` — приватный
каталог, доступа снаружи нет, система вычистит его сама при нехватке места.
Перед загрузкой каталог очищается от прошлых попыток: два APK по 15 МБ на
телефоне с забитой памятью — лишняя причина отказа.

Скачивание — OkHttp (уже в проекте), чтением по 64 КиБ, с отчётом о прогрессе
не чаще раза в 200 мс, чтобы не дёргать Compose на каждый буфер.

Дальше — две проверки, обе обязательные:

```kotlin
/**
 * 1. Сумма из релиза. Защищает от битой закачки и подменённого ассета.
 * 2. Подпись APK против своей собственной. Защищает от главного тихого
 *    сценария: APK целый, но подписан не нашим ключом — система откажет
 *    установить и покажет «Приложение не установлено» без причины.
 *    Сверяем сами и говорим, что произошло.
 */
fun verify(apk: File, expectedSha256: String): VerifyResult
```

Подпись читается из файла через `getPackageArchiveInfo` с
`GET_SIGNING_CERTIFICATES` (API 28+) или `GET_SIGNATURES` (ниже) и сравнивается
с подписью установленного приложения — по SHA-256 сертификата, не по объекту.
Не сошлось — файл удаляется немедленно, `UpdateFailure.SignatureMismatch`.

## Установка: `ApkInstaller`

Ставим через `PackageInstaller` Session API, а не через
`Intent(ACTION_VIEW)` + `FileProvider`. Причины: не нужен `FileProvider` и
внешний каталог, приходит внятный статус установки, и только этот путь умеет
ставить самообновление без системного диалога.

```kotlin
val params = PackageInstaller.SessionParams(MODE_FULL_INSTALL).apply {
    setAppPackageName(context.packageName)

    // Android 12+ разрешает приложению обновить САМО СЕБЯ без системного
    // окна, если подпись совпадает и разрешение на установку уже выдано.
    // Это не обход защиты: система проверяет всё то же самое, просто не
    // спрашивает второй раз о том, на что пользователь уже согласился.
    // Не выполнилось условие — вернётся STATUS_PENDING_USER_ACTION,
    // и мы покажем обычное подтверждение. Ниже 12 — всегда диалог.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
    }

    // setRequestUpdateOwnership НЕ включаем намеренно: заявив владение
    // обновлениями, мы заставим будущий RuStore показывать предупреждение
    // при своей же установке. Планы на магазин важнее.
}
```

Затем `createSession` → `openSession` → `openWrite` + `fsync` →
`commit(pendingIntent.intentSender)`. `PendingIntent` — широковещательный, на
`UpdateInstallReceiver`, с явным `setPackage(context.packageName)` и флагом
`FLAG_MUTABLE` (на API 31+ обязателен: систем дописывает в интент свои extras).

### `UpdateInstallReceiver`

Три ветки статуса:

- `STATUS_SUCCESS` — процесс приложения будет убит и заменён. Поэтому
  `updated_from_version_code` пишется **до** `commit`, а не здесь: после
  успешной установки этот код может не выполниться вовсе.
- `STATUS_PENDING_USER_ACTION` — система хочет подтверждение. Из интента
  достаётся `Intent.EXTRA_INTENT`. Если приложение на экране — запускаем
  активити. Если в фоне — с Android 10 запуск активити из фона запрещён,
  поэтому показываем уведомление «Нажмите, чтобы установить» с этим интентом
  внутри. Пропустить эту ветку — значит получить обновление, которое молча
  ничего не делает у пользователя в кармане.
- всё остальное (`STATUS_FAILURE*`) — `UpdateFailure.InstallFailed` с
  `EXTRA_STATUS_MESSAGE` в лог, APK удаляется.

### Разрешение на установку

В манифест добавляется:

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

На API 26+ этого мало: нужно, чтобы пользователь один раз разрешил установку
из Neiro. Проверяем `packageManager.canRequestPackageInstalls()`, и если нет —
отправляем в системные настройки:

```kotlin
Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
```

Обойти это нельзя никаким способом, и пытаться не нужно — см.
[RISKS.md § Чего мы не делаем](RISKS.md#чего-мы-не-делаем). Задача экрана —
объяснить одной фразой, зачем это, и открыть нужный переключатель сразу, а не
оставить человека бродить по настройкам.

## Когда проверяем

Два триггера, оба через один координатор:

```kotlin
object UpdateCheckCoordinator {
    private const val WORK_NAME = "update_check"

    /** Вызывается из NeiroApplication.onCreate, в общем appScope на IO. */
    fun initialize(context: Context) { … }
}
```

1. **`PeriodicWorkRequest` раз в сутки**, `ExistingPeriodicWorkPolicy.KEEP`,
   `NetworkType.CONNECTED`, `setRequiresBatteryNotLow(true)`.
   Здесь намеренно **не** самопланирующийся `OneTimeWorkRequest`, как в
   `PushKeepAliveCoordinator`: тому нужен интервал, меняющийся по времени
   суток, и он платит за это хрупкой цепочкой, которую легко оборвать (см.
   память проекта о `scheduleNext` в `finally`). Проверке обновлений хватает
   ровных суток, а периодическую работу WorkManager перепланирует сам и
   переживает перезагрузку.
2. **При старте приложения**, если с прошлой проверки прошло больше суток —
   по образцу `AutoSyncCoordinator`. Воркер может задержаться на день, если
   телефон лежал в Doze; открытие приложения — самый естественный момент
   спросить.

Скачивание автоматически **не** запускается никогда — только по нажатию
пользователя. Пятнадцать мегабайт по мобильному интернету без спроса — не то
поведение, которое прощают.

## Уведомление

Отдельный канал `app_updates`, важность `DEFAULT` (не срочно, звенеть не
должно), оформление — общий `NeiroNotificationBranding.apply`, как у остальных
уведомлений приложения. Текст: «Neiro 0.2.0 — доступно обновление», в теле —
первая строка описания релиза. Нажатие открывает экран «О программе».

Об одной и той же версии уведомляем один раз (`notified_version_code`).
Кнопка «Пропустить» в уведомлении пишет `skipped_version_code` — молчим до
следующего релиза.

Лента in-app уведомлений (`InAppNotificationStore`) **не трогается**: она
устроена вокруг событий занятий (`SessionEventType`), и «вышла версия» туда не
ложится без правки чужой модели. Признак новой версии в интерфейсе — точка на
пункте «О программе» в настройках.

## Точки встраивания

Исчерпывающий список изменений в существующих файлах. Всё остальное — новые
файлы.

| Файл | Что добавляется |
|---|---|
| `app/build.gradle.kts` | Чтение `version.properties`, `versionCode` по формуле, `buildConfigField` `UPDATE_ENABLED` и `UPDATE_REPO` |
| `app/src/main/AndroidManifest.xml` | `REQUEST_INSTALL_PACKAGES`, регистрация `UpdateInstallReceiver` (`exported="false"`) |
| `NeiroApplication.kt` | Одна строка: `UpdateCheckCoordinator.initialize(this)` в существующем `appScope.launch` |
| `ui/screens/CalendarScreen.kt` | `CalendarOverlay.About` рядом с прочими оверлеями и его ветка отрисовки — тем же способом, что `ProfitSettings` |
| `ui/settings/AppSettingsScreen.kt` | Секция «О программе» с `SettingsNavigationRow`: версия в подзаголовке, точка при доступном обновлении |
| `app/src/main/res/values/strings.xml` | Строки экрана и уведомления |

Ни один существующий класс не меняет поведения. Если самообновление целиком
выключить (`UPDATE_ENABLED = false`), приложение работает ровно как сегодня.

## Экран «О программе»

Простой список, в стиле остальных настроек (`SettingsGroupCard`,
`SettingsNavigationRow`):

```
О программе
┌────────────────────────────────────────┐
│ Neiro 0.1.0                            │
│ Установлено из GitHub · сборка 100     │
├────────────────────────────────────────┤
│ Проверить обновления            [ ⟳ ]  │
│ Последняя проверка: сегодня в 09:14    │
└────────────────────────────────────────┘

когда есть новая версия:
┌────────────────────────────────────────┐
│ Доступна 0.2.0                         │
│ Что изменилось:                        │
│ · Исправил перенос занятий             │
│ · Добавил ставки с историей            │
│                                        │
│ [ Обновить ]        [ Пропустить ]     │
│ ▓▓▓▓▓▓▓▓░░░░░░░ 54 %  8,1 из 15 МБ     │
└────────────────────────────────────────┘
```

Описание релиза приходит Markdown-ом из `body`. Рендерить Markdown нечем и не
нужно: показываем как есть, обрезая до разумной длины, со ссылкой «Открыть на
GitHub» для полного текста.

Для сборки из магазина вместо кнопки — строка «Обновления приходят из RuStore»
и ссылка на карточку приложения.

## Что проверяется тестами

Юнит-тесты (без устройства, `app/src/test`):

- разбор тега: `v0.2.0` → 200; `0.2.0`, `v0.2`, `v0.2.0-rc1`, `v1.2.300` → `null`;
- сравнение: равные версии, старее, новее, перескок через мажор;
- разбор `SHA256SUMS.txt`: нужная строка среди нескольких, лишние пробелы,
  отсутствие нужного имени;
- выбор ассета: точное имя, единственный `.apk`, два `.apk` → ошибка, ни одного → ошибка;
- политика уведомлений: та же версия дважды, пропущенная версия, следующая
  после пропущенной.

Проверяется на устройстве вручную (чек-лист — в [TASKS.md](TASKS.md#этап-11--проверка-на-устройстве)):
установка поверх, сохранность данных, отказ пользователя, отсутствие сети
посреди закачки, битая сумма, APK с чужой подписью, поведение сборки из
магазина.
