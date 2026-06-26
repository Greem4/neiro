from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

import google.auth.transport.requests
from google.oauth2 import service_account

from app.config import Settings


@dataclass(frozen=True)
class FcmSendResult:
    token_invalid: bool = False


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

    async def send_sync_push(
        self,
        *,
        token: str,
        company_id: int,
        staff_id: int,
        reason: str,
    ) -> FcmSendResult:
        if not self.is_configured:
            raise RuntimeError("FCM is not configured on the server")

        import httpx

        access_token = self._access_token()
        url = (
            f"https://fcm.googleapis.com/v1/projects/{self._project_id}/messages:send"
        )
        body = {
            "message": {
                "token": token,
                "data": {
                    "action": "sync",
                    "company_id": str(company_id),
                    "staff_id": str(staff_id),
                    "reason": reason,
                },
                "android": {
                    "priority": "HIGH",
                },
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
                token_invalid = self._is_invalid_token_error(response)
                if token_invalid:
                    return FcmSendResult(token_invalid=True)
                raise RuntimeError(
                    f"FCM error {response.status_code}: {response.text[:300]}"
                )
        return FcmSendResult()

    def _is_invalid_token_error(self, response) -> bool:
        try:
            payload = response.json()
        except json.JSONDecodeError:
            return response.status_code in {404, 410}
        error = payload.get("error", {})
        status = str(error.get("status", "")).upper()
        if status in {"NOT_FOUND", "UNREGISTERED", "INVALID_ARGUMENT"}:
            return True
        details = error.get("details", [])
        for item in details:
            error_code = str(item.get("errorCode", "")).upper()
            if error_code in {"UNREGISTERED", "INVALID_ARGUMENT"}:
                return True
        return response.status_code in {404, 410}

    def _access_token(self) -> str:
        assert self._credentials is not None
        request = google.auth.transport.requests.Request()
        self._credentials.refresh(request)
        return self._credentials.token
