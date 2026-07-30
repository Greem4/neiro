package ru.greemlab.neiro.sync

import java.time.LocalTime
import java.time.ZoneId

/**
 * Ночное окно и часовой пояс синхронизации — по МСК, независимо от таймзоны телефона.
 *
 * Тихие часы (21:00–09:00) нужны сейчас только keepalive-цепочке
 * ([ru.greemlab.neiro.push.PushKeepAliveCoordinator]): ночью она переустанавливает
 * регистрацию реже. Сами интервалы живут там же и здесь не дублируются намеренно —
 * иначе они снова разъедутся, как разъехались прежние 5 минут в этом файле с
 * фактическими 30 минутами в keepalive.
 *
 * Локального опроса YClients по таймеру больше нет (убран 25.07.2026 — изменения
 * приходят push'ом с сервера), от прежнего расписания осталось только это окно.
 */
object SyncQuietHours {

    /** Пояс, в котором YClients ведёт расписание — он же для `changed_after`. */
    val syncZone: ZoneId = ZoneId.of("Europe/Moscow")

    private val dayStart = LocalTime.of(9, 0)
    private val quietStart = LocalTime.of(21, 0)

    fun isQuietHours(
        time: LocalTime = LocalTime.now(syncZone),
    ): Boolean = time >= quietStart || time < dayStart
}
