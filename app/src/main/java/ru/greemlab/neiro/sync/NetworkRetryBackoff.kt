package ru.greemlab.neiro.sync

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Пауза между попытками достучаться до сервера, который не отвечает.
 *
 * Возврат в приложение — самое частое событие в жизни календаря: свернул,
 * развернул, переключился и обратно. Пока Pi недоступен, каждое такое движение
 * означало новый запрос, новую секунду ожидания и ту же ошибку. Здесь копится
 * пауза: 30 секунд, минута, две — до четверти часа.
 *
 * Состояние живёт в памяти процесса намеренно: перезапуск приложения — это как
 * раз тот случай, когда попробовать стоит сразу.
 */
class NetworkRetryBackoff(
    private val firstDelayMillis: Long = 30_000L,
    private val maxDelayMillis: Long = 15 * 60_000L,
) {

    private val failures = AtomicInteger(0)
    private val nextAttemptAt = AtomicLong(0L)

    /** Пора ли пробовать снова. */
    fun allowsAttempt(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis >= nextAttemptAt.get()

    /** Связь есть — следующая попытка без задержки. */
    fun onSuccess() {
        failures.set(0)
        nextAttemptAt.set(0L)
    }

    /** Сервер не ответил — откладываем следующую попытку. */
    fun onFailure(nowMillis: Long = System.currentTimeMillis()) {
        val attempt = failures.getAndIncrement()
        val delay = (firstDelayMillis shl attempt.coerceAtMost(MAX_SHIFT))
            .coerceIn(firstDelayMillis, maxDelayMillis)
        nextAttemptAt.set(nowMillis + delay)
    }

    private companion object {
        /** Дальше сдвигать бессмысленно: и так упрёмся в maxDelayMillis. */
        const val MAX_SHIFT = 16
    }
}
