from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    api_key: str
    admin_api_key: str
    database_path: str = "/data/neiro_push.db"
    poll_interval_seconds: int = 10
    poll_night_interval_seconds: int = 3600
    quiet_start_hour: int = 23
    fcm_credentials_path: str = "/secrets/fcm-service-account.json"
    fcm_project_id: str = ""
    token_encryption_key: str = ""
    host: str = "0.0.0.0"
    port: int = 8012
    log_level: str = "info"

    # Партнёрский токен теперь живёт здесь, а не в колонке аккаунта: он один на
    # весь сервис, и в БД оказывался только потому, что приезжал с телефона.
    # company_id — филиал по умолчанию при входе, телефон его больше не знает.
    yclients_partner_token: str = ""
    yclients_company_id: int = 0

    yclients_base_url: str = "https://api.yclients.com/api/v1"
    yclients_accept: str = "application/vnd.yclients.v2+json"
    horizon_days: int = 62
    yclients_max_pages: int = 100


@lru_cache
def get_settings() -> Settings:
    return Settings()
