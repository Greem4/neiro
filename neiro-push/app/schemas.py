from pydantic import BaseModel, Field


class AckEventsRequest(BaseModel):
    last_event_id: int = Field(ge=0)


class EventPayload(BaseModel):
    id: int
    staff_id: int
    type: str
    client_name: str
    date: str
    time: str
    kind: str
    prev_date: str | None = None
    prev_time: str | None = None


class EventsResponse(BaseModel):
    events: list[EventPayload]
    last_event_id: int
    has_more: bool


class HealthResponse(BaseModel):
    ok: bool = True
    service: str = "neiro-push"
    fcm_configured: bool
    accounts: int
    devices: int
    events_today: int
    poll_day_seconds: int
    poll_night_seconds: int
