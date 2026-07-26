package ru.greemlab.neiro.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.runBlocking
import ru.greemlab.neiro.data.network.YClientsRepository

/**
 * FCM: сервер шлёт события занятий (`session_events`) прямо в payload — правим
 * календарь и показываем уведомление без похода в YClients. Если пуш не влез в
 * лимит, сервер шлёт нудж (`sync_events`) — забираем через WorkManager, сеть в
 * FCM-сервисе не место (app.md §6.3).
 */
class NeiroFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["action"]) {
            "session_events" -> handleSessionEvents(message)
            "sync_events" -> PushEventsSyncCoordinator.enqueue(applicationContext)
        }
    }

    private fun handleSessionEvents(message: RemoteMessage) {
        val myStaffId = YClientsRepository.getInstance(applicationContext).staffId ?: return
        val raw = message.data["events"] ?: return

        val parsed = runCatching {
            GSON.fromJson<List<PushSessionEvent>>(raw, EVENTS_LIST_TYPE)
        }.getOrNull() ?: return

        // Отсев чужого staff_id — до того, как события разойдутся по путям
        // доставки (уведомление, календарь). См. app.md §2.5.
        val mine = parsed.filter { event ->
            val ok = event.staffId == myStaffId
            if (!ok) Log.w(TAG, "чужое событие: staff=${event.staffId}, своё=$myStaffId")
            ok
        }

        // Календарь и уведомление — независимо: ошибка применения не должна
        // съесть push (app.md §5.7). Порядок обратный означал бы «не смогли
        // поправить календарь — не сказали пользователю».
        runCatching { runBlocking { PushEventCalendarApplier.apply(applicationContext, mine) } }
        PushEventNotifier.notify(applicationContext, mine.mapNotNull { it.toSessionEvent() })

        // Максимумом с уже сохранённым — push'и могут прийти не по порядку.
        message.data["last_event_id"]?.toLongOrNull()?.let {
            PushEventsCursor.markSeen(applicationContext, it)
        }
    }

    override fun onNewToken(token: String) {
        PushRegistrar.onFcmTokenRefresh(applicationContext, token)
    }

    private companion object {
        const val TAG = "NeiroFcmService"
        val GSON = Gson()
        val EVENTS_LIST_TYPE = object : TypeToken<List<PushSessionEvent>>() {}.type
    }
}
