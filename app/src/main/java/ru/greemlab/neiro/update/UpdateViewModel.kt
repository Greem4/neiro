package ru.greemlab.neiro.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.greemlab.neiro.BuildConfig
import java.io.File

/**
 * Состояние экрана «О программе».
 *
 * Скачивание и установка запускаются только отсюда, то есть только по нажатию
 * пользователя: фоновая проверка (`UpdateCheckWorker`) умеет ровно узнать и
 * сказать, дальше — его решение.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * Своё поле вместо `getApplication()`: тот объявлен как `<T : Application> T`,
     * и в местах, где ждут `Context`, вывод типа спотыкается.
     */
    private val app: Application = application

    private val preferences = UpdatePreferences.get(application)
    private val downloader = UpdateDownloader()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _autoCheckEnabled = MutableStateFlow(preferences.isAutoCheckEnabled)
    val autoCheckEnabled: StateFlow<Boolean> = _autoCheckEnabled.asStateFlow()

    private val _lastCheckAt = MutableStateFlow(preferences.lastCheckEpochMillis)
    val lastCheckAt: StateFlow<Long> = _lastCheckAt.asStateFlow()

    val versionName: String = BuildConfig.VERSION_NAME
    val versionCode: Int = BuildConfig.VERSION_CODE

    /** «Обновлено до 0.1.2» — показывается один раз после успешной установки. */
    private val _justUpdatedTo = MutableStateFlow<String?>(null)
    val justUpdatedTo: StateFlow<String?> = _justUpdatedTo.asStateFlow()

    init {
        UpdateChannelGate.blockReason(application)?.let { _state.value = UpdateState.Blocked(it) }
        restoreAfterInstall()
        observeInstallStatus()
    }

    /**
     * Что осталось от прошлого запуска: отметка об установке и, может быть,
     * скачанный, но не поставленный APK. Второе важнее, чем кажется: без него
     * человек, закрывший системный диалог, качал бы те же 15 МБ заново.
     */
    private fun restoreAfterInstall() {
        val updatedFrom = preferences.consumeUpdatedFrom()
        if (updatedFrom in 1 until BuildConfig.VERSION_CODE) {
            _justUpdatedTo.value = BuildConfig.VERSION_NAME
            UpdateDownloader.clearDownloads(app)
            preferences.clearPendingApk()
            UpdateNotifier.cancel(app)
            return
        }

        val pendingPath = preferences.pendingApkPath
        val pendingVersion = preferences.pendingVersionCode
        if (pendingPath.isNullOrBlank() || pendingVersion <= BuildConfig.VERSION_CODE) {
            // Файл от старой или уже установленной версии — только место занимает.
            if (pendingPath != null) {
                UpdateDownloader.clearDownloads(app)
                preferences.clearPendingApk()
            }
            return
        }

        val apk = File(pendingPath)
        val version = ReleaseVersion.fromVersionCode(pendingVersion)
        if (!apk.isFile || apk.length() == 0L || version == null) {
            preferences.clearPendingApk()
            return
        }

        // Ссылок и заметок после перезапуска нет — для установки они и не нужны.
        val info = UpdateInfo(
            version = version,
            title = "Neiro ${version.versionName}",
            notes = "",
            releaseUrl = "",
            apkName = apk.name,
            apkUrl = "",
            apkSizeBytes = apk.length(),
            checksumsUrl = "",
        )
        _state.value = UpdateState.ReadyToInstall(info, apk)
    }

    /** Отметку прочитали и показали — второй раз не надо. */
    fun dismissJustUpdated() {
        _justUpdatedTo.value = null
    }

    /** Кнопка «Проверить обновления». Ручная проверка суточный троттлинг игнорирует. */
    fun check(force: Boolean = true) {
        if (_state.value.isBusy) return
        val blocked = UpdateChannelGate.blockReason(app)
        if (blocked != null) {
            _state.value = UpdateState.Blocked(blocked)
            return
        }

        _state.value = UpdateState.Checking
        viewModelScope.launch {
            val status = UpdateCheckCoordinator.checkNow(app, force)
            _lastCheckAt.value = preferences.lastCheckEpochMillis
            _state.value = when (status) {
                is UpdateStatus.Available -> UpdateState.Available(status.info)
                is UpdateStatus.UpToDate -> UpdateState.UpToDate(status.checkedAt)
                is UpdateStatus.Throttled -> UpdateState.UpToDate(status.checkedAt)
                is UpdateStatus.Blocked -> UpdateState.Blocked(status.why)
                is UpdateStatus.Failed -> UpdateState.Failed(status.failure, null)
            }
        }
    }

    /**
     * Скачать, сверить и отдать установщику — три шага одной кнопкой, потому что
     * пользователю это один поступок. Каждый шаг умеет остановить цепочку.
     */
    fun downloadAndInstall(info: UpdateInfo) {
        if (_state.value.isBusy) return

        viewModelScope.launch {
            _state.value = UpdateState.Downloading(info, 0)

            val outcome = downloader.download(app, info) { percent ->
                val current = _state.value
                if (current is UpdateState.Downloading) {
                    _state.value = current.copy(percent = percent)
                }
            }
            val apk = when (outcome) {
                is DownloadOutcome.Success -> outcome.apk
                is DownloadOutcome.Failed -> {
                    _state.value = UpdateState.Failed(outcome.failure, info)
                    return@launch
                }
            }

            _state.value = UpdateState.Verifying(info)
            val checksums = downloader.downloadChecksums(info.checksumsUrl)
            val failure = UpdateVerifier.verify(app, apk, checksums)
            if (failure != null) {
                // Файл уже удалён внутри проверки — не оставляем 15 МБ мусора.
                _state.value = UpdateState.Failed(failure, info)
                return@launch
            }

            preferences.pendingApkPath = apk.absolutePath
            preferences.pendingVersionCode = info.version.versionCode
            _state.value = UpdateState.ReadyToInstall(info, apk)
            install(info, apk)
        }
    }

    /** Отдать проверенный файл системе. Отдельно от загрузки: после отказа можно повторить. */
    fun install(info: UpdateInfo, apk: File) {
        // Признак ставим до запуска корутины: пока он выставлялся внутри неё,
        // двойное нажатие «Установить» успевало пройти оба раза и создавало две
        // сессии PackageInstaller на один файл.
        if (_state.value is UpdateState.Installing) return
        _state.value = UpdateState.Installing(info)
        viewModelScope.launch {
            if (!ApkInstaller.canInstall(app)) {
                // Разрешение выдаётся в системных настройках, обойти нельзя.
                _state.value = UpdateState.Failed(UpdateFailure.InstallRejected, info)
                return@launch
            }

            val failure = ApkInstaller.install(app, apk, info.version.versionCode)
            if (failure != null) {
                _state.value = UpdateState.Failed(failure, info)
            }
            // Иначе ждём ответа установщика — он придёт в UpdateInstallStatus.
        }
    }

    /** «Пропустить» — молчим об этой версии, пока не выйдет следующая. */
    fun skip(info: UpdateInfo) {
        preferences.skippedVersionCode = info.version.versionCode
        preferences.notifiedVersionCode = maxOf(
            preferences.notifiedVersionCode,
            info.version.versionCode,
        )
        UpdateNotifier.cancel(app)
        UpdateDownloader.clearDownloads(app)
        preferences.clearPendingApk()
        _state.value = UpdateState.UpToDate(preferences.lastCheckEpochMillis)
    }

    fun setAutoCheckEnabled(enabled: Boolean) {
        _autoCheckEnabled.value = enabled
        UpdateCheckCoordinator.onAutoCheckToggled(app, enabled)
    }

    fun needsInstallPermission(): Boolean = !ApkInstaller.canInstall(app)

    fun installPermissionIntent() = ApkInstaller.unknownSourcesSettingsIntent(app)

    /** Сбросить ошибку, чтобы экран вернулся к обычному виду. */
    fun dismissFailure() {
        if (_state.value is UpdateState.Failed) {
            _state.value = UpdateState.UpToDate(preferences.lastCheckEpochMillis)
        }
        UpdateInstallStatus.clear()
    }

    private fun observeInstallStatus() {
        viewModelScope.launch {
            UpdateInstallStatus.events.collect { event ->
                val info = _state.value.info
                when (event) {
                    null -> Unit
                    is UpdateInstallEvent.Installed -> _state.value =
                        UpdateState.UpToDate(preferences.lastCheckEpochMillis)

                    is UpdateInstallEvent.AwaitingConfirmation ->
                        if (info != null) _state.value = UpdateState.AwaitingConfirmation(info)

                    is UpdateInstallEvent.Failed ->
                        _state.value = UpdateState.Failed(event.failure, info)
                }
            }
        }
    }
}
