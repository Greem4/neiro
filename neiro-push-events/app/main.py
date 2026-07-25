from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.config import get_settings
from app.database import Database
from app.fcm import FcmSender
from app.poller import PollService
from app.security import SecretBox
from app.yclients import YClientsClient

logger = logging.getLogger(__name__)


def configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    configure_logging(settings.log_level)

    db = Database(settings.database_path)
    yclients_client = YClientsClient(settings)
    poll_service = PollService(
        settings=settings,
        database=db,
        secret_box=SecretBox(settings.token_encryption_key),
        yclients=yclients_client,
        fcm=FcmSender(settings),
    )

    app.state.db = db
    app.state.poll_service = poll_service

    poll_service.start()
    logger.info("neiro-push-events started")
    yield
    await poll_service.stop()
    await yclients_client.aclose()
    logger.info("neiro-push-events stopped")


app = FastAPI(title="Neiro Push Events", version="0.1.0", lifespan=lifespan)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "neiro-push-events"}
