package ru.greemlab.neiro.push

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.greemlab.neiro.data.network.YClientsRepository

/**
 * Догон пропущенных событий по курсору `last_event_id` (app.md §6.2) — вызывается
 * из keepalive-тика, из открытия приложения и по нуджу сервера (`sync_events`).
 */
object PushEventsSyncer {

    private const val TAG = "PushEventsSyncer"
    private const val PAGE_LIMIT = 100

    /** Верхняя граница на цикл — не зациклиться на сервере, который всегда отвечает has_more=true. */
    private const val MAX_PAGES = 10

    // syncNow может стартовать одновременно из keepalive-воркера и из onStart —
    // без Mutex оба потока прочитали бы один курсор и показали одно и то же дважды.
    private val mutex = Mutex()

    suspend fun syncNow(context: Context) {
        if (!PushConfig.isActive) return
        val appContext = context.applicationContext
        val repository = YClientsRepository.getInstance(appContext)
        if (!repository.isLoggedIn.first()) return
        val myStaffId = repository.staffId ?: return

        mutex.withLock {
            val api = PushClient.getApi() ?: return
            val deviceId = PushDeviceId.get(appContext)

            for (page in 0 until MAX_PAGES) {
                val since = PushEventsCursor.get(appContext)
                val response = runCatching {
                    api.getEvents(PushClient.authHeader(), deviceId, since, PAGE_LIMIT)
                }.getOrNull()
                // Ошибку сети — проглотить и выйти; следующий тик keepalive повторит.
                val body = response?.takeIf { it.isSuccessful }?.body() ?: return

                // В порядке возрастания id — иначе «перенос, затем отмена» ляжет наоборот.
                val ordered = body.events.sortedBy { it.id }
                val mine = ordered.filter { event ->
                    val ok = event.staffId == myStaffId
                    if (!ok) Log.w(TAG, "чужое событие в догоне: staff=${event.staffId}, своё=$myStaffId")
                    ok
                }

                // Логаут мог пройти, пока шёл запрос: события старого аккаунта в календарь
                // нового не пишем.
                if (!repository.isLoggedIn.first()) return

                // Порядок независимый: ошибка применения к календарю не должна съесть показ (§5.7).
                runCatching { PushEventCalendarApplier.apply(appContext, mine) }
                PushEventNotifier.notify(appContext, mine.mapNotNull { it.toSessionEvent() })

                // Ещё одна проверка: markSeen после сброса курсора в logout вернул бы
                // позицию чужого аккаунта, и события нового были бы пропущены.
                if (!repository.isLoggedIn.first()) return

                // Курсор и ack — сразу после показа этой страницы: обрыв сети посреди
                // догона не должен приводить к повторному показу уже отданных событий.
                PushEventsCursor.markSeen(appContext, body.lastEventId)
                runCatching {
                    api.ackEvents(PushClient.authHeader(), deviceId, AckEventsRequest(body.lastEventId))
                }

                if (!body.hasMore) return
            }
        }
    }
}
