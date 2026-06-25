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


class HealthResponse(BaseModel):
    ok: bool = True
    service: str = "neiro-push"
    fcm_configured: bool
    accounts: int
    devices: int
    poll_day_seconds: int
    poll_night_seconds: int
