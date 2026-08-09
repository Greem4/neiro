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

        var failed = false
        try {
            // Сверка с сервером — до догона: заодно выясняется, не отозван ли
            // доступ и не нужен ли повторный вход, и тогда за событиями идти
            // уже незачем.
            val refreshOutcome = runCatching { PushRegistrar.refreshDeviceState(applicationContext) }
                .onFailure { if (it is CancellationException) throw it }

            runCatching { PushEventsSyncer.syncNow(applicationContext) }
                .onFailure { if (it is CancellationException) throw it }

            failed = refreshOutcome.isFailure || refreshOutcome.getOrNull() == false

            // Result.retry() здесь был бы вторым планировщиком поверх scheduleNext:
            // отретраенная работа не в терминальном состоянии, поэтому APPEND_OR_REPLACE
            // не заменяет её, а дописывает звено — цепочка росла бы с каждой ошибкой.
            return Result.success()
        } finally {
            if (!isStopped) {
                PushKeepAliveCoordinator.scheduleNext(applicationContext, afterFailure = failed)
            }
        }
    }
}
