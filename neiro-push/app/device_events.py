"""Догон пропущенных событий по курсору (API.md § События).

Пуш с полным payload доезжает не всегда: телефон бывает офлайн, а сообщение,
не влезшее в лимит FCM, сервер заменяет нуджем `sync_events`. И в том, и в
другом случае приложение приходит сюда и забирает всё, что накопилось после
своего `last_ack_event_id`.

Отличие от `neiro-push-events`: `device_id` больше не в пути и не в параметрах —
устройство определяется по `device_token`, а значит попросить чужую ленту
нечем.
"""

from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, Query, Response, status

from app.auth import DeviceContext, get_database, require_device
from app.database import Database
from app.poller import MOSCOW
from app.schemas import AckEventsRequest, EventPayload, EventsResponse

router = APIRouter(prefix="/v1")


@router.get("/events", response_model=EventsResponse)
async def list_events(
    since: int = Query(default=0, ge=0),
    limit: int = Query(default=100, ge=1, le=500),
    context: DeviceContext = Depends(require_device),
    db: Database = Depends(get_database),
) -> EventsResponse:
    """Лента событий аккаунта после `since`.

    Берётся `require_device`, а не `require_live_account`: события уже лежат в
    базе, и протухший `user_token` их получению не мешает. Отдать накопленное
    телефону, которому предстоит повторный вход, — ровно то, что нужно.
    """
    # Горизонт §6.4 плана: догон не тащит события про уже прошедшие занятия —
    # уведомление «перенос на вчера» пользы не приносит.
    today = datetime.now(MOSCOW).date().isoformat()
    rows = db.list_events_since(
        context.account.id, since, limit=limit + 1, min_date=today
    )
    has_more = len(rows) > limit
    rows = rows[:limit]

    events = [
        EventPayload(
            id=row.id,
            staff_id=context.account.staff_id,
            type=row.type,
            client_name=row.client_name,
            date=row.date,
            time=row.time,
            kind=row.kind,
            prev_date=row.prev_date,
            prev_time=row.prev_time,
        )
        for row in rows
    ]
    # Пустая страница не двигает курсор назад: клиенту возвращается его же since.
    last_event_id = events[-1].id if events else since
    return EventsResponse(events=events, last_event_id=last_event_id, has_more=has_more)


@router.post("/events/ack", status_code=status.HTTP_204_NO_CONTENT)
async def ack_events(
    body: AckEventsRequest,
    context: DeviceContext = Depends(require_device),
    db: Database = Depends(get_database),
) -> Response:
    """Курсор только вперёд.

    Повторно доехавший ack из прошлого догона иначе откатил бы позицию, и
    телефон получил бы уже показанные события ещё раз.
    """
    current = context.device.last_ack_event_id or 0
    if body.last_event_id > current:
        db.update_device_ack(context.device.device_id, body.last_event_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
