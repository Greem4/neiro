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


class LoginRequest(BaseModel):
    login: str = Field(min_length=3, max_length=200)
    # Пароль намеренно без примеров и описаний: модель попадает в openapi.json,
    # который отдаётся наружу.
    password: str = Field(min_length=1, max_length=200)
    device_id: str = Field(min_length=8, max_length=128)
    # Пустой допустим: Firebase выдаёт токен не всегда (нет Google-сервисов,
    # сбой, сборка без google-services.json). Вход от этого зависеть не должен —
    # расписание и деньги работают и без пушей, а токен донесёт
    # POST /v1/devices/fcm, когда появится.
    fcm_token: str = Field(default="", max_length=4096)
    label: str | None = Field(default=None, max_length=120)
    app_version: str | None = Field(default=None, max_length=40)


class AccountPayload(BaseModel):
    company_id: int
    staff_id: int
    user_name: str | None = None
    avatar_url: str | None = None


class LoginResponse(BaseModel):
    device_token: str
    account: AccountPayload
    last_event_id: int


class SessionResponse(BaseModel):
    account: AccountPayload
    reauth_required: bool
    last_event_id: int


class FcmTokenRequest(BaseModel):
    fcm_token: str = Field(min_length=20, max_length=4096)
