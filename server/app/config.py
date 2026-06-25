from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    api_key: str
    database_path: str = "/data/neiro_push.db"
    poll_interval_seconds: int = 15
    poll_night_interval_seconds: int = 3600
    fcm_credentials_path: str = "/secrets/fcm-service-account.json"
    fcm_project_id: str = ""
    token_encryption_key: str = ""
    host: str = "0.0.0.0"
    port: int = 8010
    log_level: str = "info"

    yclients_base_url: str = "https://api.yclients.com/api/v1"
    yclients_accept: str = "application/vnd.yclients.v2+json"
    horizon_days: int = 62


@lru_cache
def get_settings() -> Settings:
    return Settings()
