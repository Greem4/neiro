from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import google.auth.transport.requests
import httpx
from google.oauth2 import service_account

from app.config import Settings

# Лимит FCM ~4КБ на сообщение (data-payload), запас под остальные поля — §6.1.
MAX_EVENTS_PAYLOAD_BYTES = 3072


@dataclass(frozen=True)
class FcmSendResult:
    token_invalid: bool = False
    nudged: bool = False


class FcmSender:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._credentials = None
        self._project_id = settings.fcm_project_id

        credentials_path = Path(settings.fcm_credentials_path)
        if credentials_path.exists():
            self._credentials = service_account.Credentials.from_service_account_file(
                str(credentials_path),
                scopes=["https://www.googleapis.com/auth/firebase.messaging"],
            )
            if not self._project_id:
                with credentials_path.open("r", encoding="utf-8") as handle:
                    payload = json.load(handle)
                self._project_id = payload.get("project_id", "")

    @property
    def is_configured(self) -> bool:
        return self._credentials is not None and bool(self._project_id)

    async def send_events_push(
        self,
        *,
        token: str,
        events: list[dict],
        last_event_id: int,
    ) -> FcmSendResult:
        """Шлёт `session_events`, либо `sync_events` при переполнении (§6.1)."""
        if not self.is_configured:
            raise RuntimeError("FCM is not configured on the server")

        events_json = json.dumps(events, ensure_ascii=False, separators=(",", ":"))
        nudged = len(events_json.encode("utf-8")) > MAX_EVENTS_PAYLOAD_BYTES
        data = (
            {"action": "sync_events", "last_event_id": str(last_event_id)}
            if nudged
            else {
                "action": "session_events",
                "events": events_json,
                "last_event_id": str(last_event_id),
            }
        )

        access_token = self._access_token()
        url = f"https://fcm.googleapis.com/v1/projects/{self._project_id}/messages:send"
        body = {
            "message": {
                "token": token,
                "data": data,
                "android": {"priority": "HIGH"},
            }
        }

        async with httpx.AsyncClient(timeout=20.0) as client:
            response = await client.post(
                url,
                headers={
                    "Authorization": f"Bearer {access_token}",
                    "Content-Type": "application/json",
                },
                json=body,
            )
            if response.status_code >= 400:
                if self._is_invalid_token_error(response):
                    return FcmSendResult(token_invalid=True)
                raise RuntimeError(
                    f"FCM error {response.status_code}: {response.text[:300]}"
                )
        return FcmSendResult(nudged=nudged)

    def _is_invalid_token_error(self, response: httpx.Response) -> bool:
        """Токен мёртв — устройство можно удалять.

        INVALID_ARGUMENT сюда НЕ входит: FCM отдаёт его и на нашу собственную
        ошибку в теле сообщения, а не только на мёртвый токен. Удалять по нему
        устройство — значит терять живые телефоны из-за своей же опечатки.
        """
        try:
            payload = response.json()
        except json.JSONDecodeError:
            return response.status_code in {404, 410}
        error = payload.get("error", {})
        status = str(error.get("status", "")).upper()
        if status in {"NOT_FOUND", "UNREGISTERED"}:
            return True
        details = error.get("details", [])
        for item in details:
            error_code = str(item.get("errorCode", "")).upper()
            if error_code == "UNREGISTERED":
                return True
        return response.status_code in {404, 410}

    def _access_token(self) -> str:
        assert self._credentials is not None
        # refresh() — синхронный сетевой вызов, блокирует event loop.
        # Токен живёт час, поэтому ходим в Google только когда он протух.
        if not self._credentials.valid:
            request = google.auth.transport.requests.Request()
            self._credentials.refresh(request)
        return self._credentials.token
