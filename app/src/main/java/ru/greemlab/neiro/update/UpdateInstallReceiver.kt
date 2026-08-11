package ru.greemlab.neiro.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Чем закончилась попытка установки — для экрана «О программе». */
sealed interface UpdateInstallEvent {

    /** Система приняла APK. Обычно за этим сразу следует смерть процесса. */
    data class Installed(val versionCode: Int) : UpdateInstallEvent

    /** Установщик хочет подтверждение пользователя — окно уже показано или ушло в шторку. */
    data object AwaitingConfirmation : UpdateInstallEvent

    data class Failed(val failure: UpdateFailure, val message: String?) : UpdateInstallEvent
}

/**
 * Результат установки живёт в процессе, а не в настройках: экран «О программе»
 * подписывается на него, а переживать перезапуск ему незачем — после успешной
 * установки процесс всё равно новый, и там работает `updated_from_version_code`.
 */
object UpdateInstallStatus {

    private val _events = MutableStateFlow<UpdateInstallEvent?>(null)
    val events: StateFlow<UpdateInstallEvent?> = _events

    fun publish(event: UpdateInstallEvent) {
        _events.value = event
    }

    fun clear() {
        _events.value = null
    }
}

/**
 * Ответ `PackageInstaller`. Три ветки, и пропустить нельзя ни одну — особенно
 * `STATUS_PENDING_USER_ACTION`: без неё обновление молча ничего не делает у
 * пользователя в кармане.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val appContext = context.applicationContext
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    Log.w(TAG, "Система просит подтверждение, но интента в ответе нет")
                    fail(appContext, UpdateFailure.InstallFailed, message)
                    return
                }
                requestUserConfirmation(appContext, confirm)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                // Сюда можем и не доехать: процесс убивают почти сразу. Всё, что
                // нужно помнить об обновлении, записано до commit.
                Log.i(TAG, "Обновление установлено, версия $versionCode")
                UpdateDownloader.clearDownloads(appContext)
                UpdatePreferences.get(appContext).clearPendingApk()
                UpdateNotifier.cancel(appContext)
                UpdateInstallStatus.publish(UpdateInstallEvent.Installed(versionCode))
            }

            else -> {
                Log.w(TAG, "Установка не удалась: статус $status, $message")
                val failure = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    // Пользователь нажал «Отмена» — это не поломка, и красным
                    // про это говорить не надо.
                    UpdateFailure.InstallRejected
                } else {
                    UpdateFailure.InstallFailed
                }
                fail(appContext, failure, message)
            }
        }
    }

    private fun fail(context: Context, failure: UpdateFailure, message: String?) {
        UpdatePreferences.get(context).clearInstallAttempt()
        UpdateInstallStatus.publish(UpdateInstallEvent.Failed(failure, message))
    }

    /**
     * Приложение на экране — показываем системное окно сразу. В фоне запуск
     * активити с Android 10 запрещён, поэтому кладём тот же интент в
     * уведомление: иначе установка тихо повиснет.
     */
    private fun requestUserConfirmation(context: Context, confirm: Intent) {
        UpdateInstallStatus.publish(UpdateInstallEvent.AwaitingConfirmation)
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(Lifecycle.State.STARTED)

        if (foreground) {
            val started = runCatching { context.startActivity(confirm) }.isSuccess
            if (started) return
            Log.w(TAG, "Окно подтверждения из фона не открылось — уходим в уведомление")
        }
        UpdateNotifier.notifyInstallReady(context, confirm)
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "ru.greemlab.neiro.update.INSTALL_STATUS"
        const val EXTRA_VERSION_CODE = "version_code"

        private const val TAG = "UpdateInstall"
    }
}
