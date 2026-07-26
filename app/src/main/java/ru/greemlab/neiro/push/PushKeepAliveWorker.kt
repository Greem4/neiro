package ru.greemlab.neiro.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import ru.greemlab.neiro.data.network.YClientsRepository

/**
 * Периодически догоняет пропущенные события (backup на случай, если FCM не
 * долетел — Doze, экономия батареи) и обновляет регистрацию на push-сервере
 * (FCM-токен мог протухнуть). Календарь по таймеру не опрашивает — изменения
 * приходят push'ом или тем же догоном.
 *
 * Гарантирует, что следующий запуск планируется даже при ошибке — иначе цепочка
 * keepalive ломается до перезапуска приложения. Исключение — isStopped (воркер
 * остановлен явно, например logout): цепочку не воскрешаем.
 */
class PushKeepAliveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!PushConfig.isActive) return Result.success()

        val repository = YClientsRepository.getInstance(applicationContext)
        if (!repository.isLoggedIn.first()) return Result.success()

        try {
            // Догон — до регистрации: если пропущенные события есть, показать их
            // раньше, чем обновится FCM-токен (app.md §6.3).
            runCatching { PushEventsSyncer.syncNow(applicationContext) }
                .onFailure { if (it is CancellationException) throw it }

            val registerOutcome = runCatching { PushRegistrar.registerNow(applicationContext) }
                .onFailure { if (it is CancellationException) throw it }

            val failed = registerOutcome.isFailure || registerOutcome.getOrNull() == false

            return if (failed) Result.retry() else Result.success()
        } finally {
            if (!isStopped) {
                PushKeepAliveCoordinator.scheduleNext(applicationContext)
            }
        }
    }
}
