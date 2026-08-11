import base64
import hashlib
import hmac
import secrets

from cryptography.fernet import Fernet, InvalidToken


def generate_api_key() -> str:
    return secrets.token_urlsafe(32)


def generate_device_token() -> str:
    """32 случайных байта в base64url — то, что уезжает на телефон.

    Возвращается вызывающему ровно один раз: в БД ложится только хэш, и
    восстановить токен из дампа базы нельзя.
    """
    return secrets.token_urlsafe(32)


def hash_device_token(token: str) -> str:
    """SHA-256 без соли — намеренно.

    Токен и так 256 бит случайности, перебирать нечего, а детерминированный
    хэш нужен, чтобы искать устройство по токену одним индексированным
    запросом (`WHERE token_hash = ?`).
    """
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


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
