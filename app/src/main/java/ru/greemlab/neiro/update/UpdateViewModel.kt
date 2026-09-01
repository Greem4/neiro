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

    /**
     * Нужно ли разрешение «установка из неизвестных источников».
     *
     * Держим состоянием, а не считаем на месте: под этим
     * `packageManager.canRequestPackageInstalls()`, то есть синхронный вызов в
     * PackageManager. Из тела композиции он уходил на каждый кадр прогресса —
     * до пяти раз в секунду (аудит 14.08.26, A2).
     */
    private val _needsInstallPermission = MutableStateFlow(!ApkInstaller.canInstall(application))
    val needsInstallPermission: StateFlow<Boolean> = _needsInstallPermission.asStateFlow()

    init {
        UpdateChannelGate.blockReason(application)?.let { _state.value = UpdateState.Blocked(it) }
        restorePreviousState()
        observeInstallStatus()
    }

    /**
     * Что осталось от прошлого запуска: отметка об установке, скачанный, но не
     * поставленный APK, и найденное обновление, которое ещё не поставили.
     *
     * Последнее — то, ради чего экран открывается готовым: человек, пришедший
     * по уведомлению о версии, видит предложение обновиться сразу, а не жмёт
     * «Проверить обновления», чтобы приложение узнало то, что уже знает.
     * Скачанный APK при этом важнее найденной версии: качать те же 15 МБ
     * заново из-за закрытого системного диалога незачем.
     */
    private fun restorePreviousState() {
        // Сборке из магазина или debug обновляться нечем: ни предложения, ни
        // готового файла показывать нельзя, даже если они остались от прошлой.
        if (_state.value is UpdateState.Blocked) return

        val updatedFrom = preferences.consumeUpdatedFrom()
        if (updatedFrom in 1 until BuildConfig.VERSION_CODE) {
            _justUpdatedTo.value = BuildConfig.VERSION_NAME
            UpdateDownloader.clearDownloads(app)
            preferences.clearPendingApk()
            // Предложение исполнено — иначе экран предлагал бы поставить то,
            // что только что поставили.
            preferences.availableUpdate = null
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
            restoreOffer()
            return
        }

        val apk = File(pendingPath)
        val version = ReleaseVersion.fromVersionCode(pendingVersion)
        if (!apk.isFile || apk.length() == 0L || version == null) {
            preferences.clearPendingApk()
            restoreOffer()
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

    /**
     * Найденное прошлой проверкой обновление — на экран, без похода в сеть.
     * Устаревшую запись (обновились, пропустили) отсекает само хранилище.
     */
    private fun restoreOffer() {
        val offer = preferences.usableUpdateOffer() ?: return
        _state.value = UpdateState.Available(offer)
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
        preferences.availableUpdate = null
        UpdateNotifier.cancel(app)
        UpdateDownloader.clearDownloads(app)
        preferences.clearPendingApk()
        _state.value = UpdateState.UpToDate(preferences.lastCheckEpochMillis)
    }

    fun setAutoCheckEnabled(enabled: Boolean) {
        _autoCheckEnabled.value = enabled
        UpdateCheckCoordinator.onAutoCheckToggled(app, enabled)
    }

    /**
     * Вернулись из системных настроек — пересчитать. Само значение не меняется:
     * без этого подсказка «разрешите установку» висела, пока экран не
     * пересоберётся по другой причине.
     */
    fun refreshInstallPermission() {
        _needsInstallPermission.value = !ApkInstaller.canInstall(app)
    }

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
                    is UpdateInstallEvent.Installed -> {
                        // Поставили — предлагать больше нечего. Обычно процесс
                        // после установки убивают, но если он дожил, экран не
                        // должен звать обновляться до уже стоящей версии.
                        preferences.availableUpdate = null
                        _state.value = UpdateState.UpToDate(preferences.lastCheckEpochMillis)
                    }

                    is UpdateInstallEvent.AwaitingConfirmation ->
                        if (info != null) _state.value = UpdateState.AwaitingConfirmation(info)

                    is UpdateInstallEvent.Failed ->
                        _state.value = UpdateState.Failed(event.failure, info)
                }
            }
        }
    }
}
