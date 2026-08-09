package ru.greemlab.neiro.push

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Догон пропущенных событий (docs/neiro-push/API.md § События).
 *
 * `device_id` в путях больше нет: устройство сервер определяет по
 * `device_token`, который подставляет интерцептор
 * [ru.greemlab.neiro.data.network.YClientsClient]. Вход, выход и обновление
 * токена FCM живут в `NeiroApi` — они часть сессии, а не ленты событий.
 */
interface PushApi {

    @GET("v1/events")
    suspend fun getEvents(
        @Query("since") since: Long,
        @Query("limit") limit: Int = 100,
    ): Response<EventsResponse>

    @POST("v1/events/ack")
    suspend fun ackEvents(
        @Body body: AckEventsRequest,
    ): Response<Unit>
}

data class AckEventsRequest(
    @SerializedName("last_event_id") val lastEventId: Long,
)

/** Ответ догона — `events` зеркалит `EventPayload` через уже существующий [PushSessionEvent]. */
data class EventsResponse(
    val events: List<PushSessionEvent>,
    @SerializedName("last_event_id") val lastEventId: Long,
    @SerializedName("has_more") val hasMore: Boolean,
)
