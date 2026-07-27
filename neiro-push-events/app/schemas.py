from pydantic import BaseModel, Field


class RegisterDeviceRequest(BaseModel):
    device_id: str = Field(min_length=8, max_length=128)
    fcm_token: str = Field(min_length=20, max_length=4096)
    company_id: int = Field(gt=0)
    staff_id: int = Field(gt=0)
    partner_token: str = Field(min_length=10, max_length=4096)
    user_token: str = Field(min_length=10, max_length=4096)
    label: str | None = Field(default=None, max_length=120)
    app_version: str | None = Field(default=None, max_length=40)


class RegisterDeviceResponse(BaseModel):
    ok: bool = True
    account_id: int
    device_id: str
    last_event_id: int


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
    service: str = "neiro-push-events"
    fcm_configured: bool
    accounts: int
    devices: int
    events_today: int
    poll_day_seconds: int
    poll_night_seconds: int
