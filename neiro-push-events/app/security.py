import base64
import hashlib
import hmac
import secrets

from cryptography.fernet import Fernet, InvalidToken


def generate_api_key() -> str:
    return secrets.token_urlsafe(32)


def derive_fernet_key(secret: str) -> bytes:
    digest = hashlib.sha256(secret.encode("utf-8")).digest()
    return base64.urlsafe_b64encode(digest)


class SecretBox:
    def __init__(self, secret: str) -> None:
        if not secret:
            raise ValueError("TOKEN_ENCRYPTION_KEY is required")
        self._fernet = Fernet(derive_fernet_key(secret))

    def encrypt(self, value: str) -> str:
        return self._fernet.encrypt(value.encode("utf-8")).decode("utf-8")

    def decrypt(self, value: str) -> str:
        try:
            return self._fernet.decrypt(value.encode("utf-8")).decode("utf-8")
        except InvalidToken as exc:
            raise ValueError("failed to decrypt stored secret") from exc


def constant_time_equals(left: str, right: str) -> bool:
    return hmac.compare_digest(left.encode("utf-8"), right.encode("utf-8"))
