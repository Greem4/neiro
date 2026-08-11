"""Прокси к YClients: приложение ходит сюда вместо api.yclients.com.

Три правила, на которых стоит вся конструкция (ARCHITECTURE.md § Прокси):

1. **Тело ответа пробрасывается как есть** — вместе с кодом, включая `success`,
   `data`, `meta`. Прокси не разбирает и не «улучшает» JSON: иначе пришлось бы
   переписать `YClientsModels`, `SessionParser` и всё, что на них завязано.
2. **`company_id` и `staff_id` подставляет сервер.** В запросе их нет ни в
   пути, ни в параметрах — телефон физически не может попросить чужие записи.
3. **Пагинация остаётся на клиенте.** `page` и `count` пробрасываются как есть.
"""

from __future__ import annotations

import logging

import httpx
from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response, status

from app.auth import DeviceContext, get_database, get_secret_box, get_yclients, require_live_account
from app.database import Database
from app.security import SecretBox
from app.yclients import YClientsClient

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/v1/yclients")

# Страховка от неожиданно огромной выдачи (API.md § Лимиты). Ответ уже целиком
# в памяти — проверяем размер перед тем, как отдать его дальше.
MAX_RESPONSE_BYTES = 5 * 1024 * 1024


async def _passthrough(
    request: Request,
    context: DeviceContext,
    path: str,
    params: dict,
) -> Response:
    """Один запрос к YClients и единый разбор отказов для всех семи эндпоинтов."""
    db: Database = request.app.state.db
    secret_box: SecretBox = request.app.state.secret_box
    yclients: YClientsClient = request.app.state.yclients

    user_token = secret_box.decrypt(context.account.user_token_enc)

    try:
        response = await yclients.fetch_raw(path, user_token=user_token, params=params)
    except httpx.TimeoutException:
        logger.warning("YClients timeout on %s", path)
        raise HTTPException(
            status_code=status.HTTP_504_GATEWAY_TIMEOUT, detail="upstream_timeout"
        ) from None
    except httpx.HTTPError as exc:
        # Только тип: в str() у httpx уезжает полный URL с параметрами.
        logger.warning("YClients unreachable on %s: %s", path, type(exc).__name__)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY, detail="upstream_error"
        ) from None

    if response.status_code == 401:
        triggered = db.note_upstream_auth_failure(context.account.id)
        if triggered:
            logger.warning(
                "account %s: three 401 in a row, reauth required", context.account.id
            )
        else:
            logger.info("account %s: 401 from YClients, not yet fatal", context.account.id)
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="reauth_required")

    if response.status_code >= 500:
        logger.warning("YClients returned %s on %s", response.status_code, path)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY, detail="upstream_error"
        )

    # Дошли до осмысленного ответа — значит токен жив, серия прервана.
    db.clear_auth_failures(context.account.id)

    if len(response.content) > MAX_RESPONSE_BYTES:
        logger.warning(
            "YClients response too large on %s: %s bytes", path, len(response.content)
        )
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY, detail="upstream_too_large"
        )

    # Код и тело — ровно те, что отдал YClients. 403 на зарплате у сотрудника
    # без прав и 422 на неверном диапазоне проходят насквозь: приложение уже
    # умеет их разбирать.
    return Response(
        content=response.content,
        status_code=response.status_code,
        media_type=response.headers.get("content-type", "application/json"),
    )


@router.get("/records")
async def records(
    request: Request,
    start_date: str = Query(...),
    end_date: str = Query(...),
    page: int = Query(default=1, ge=1),
    count: int = Query(default=100, ge=1, le=1000),
    changed_after: str | None = Query(default=None),
    with_deleted: int | None = Query(default=None),
    context: DeviceContext = Depends(require_live_account),
) -> Response:
    return await _passthrough(
        request,
        context,
        f"/records/{context.account.company_id}",
        {
            "start_date": start_date,
            "end_date": end_date,
            "staff_id": context.account.staff_id,
            "page": page,
            "count": count,
            "changed_after": changed_after,
            "with_deleted": with_deleted,
        },
    )


@router.get("/clients")
async def clients(
    request: Request,
    page: int = Query(default=1, ge=1),
    count: int = Query(default=100, ge=1, le=1000),
    context: DeviceContext = Depends(require_live_account),
) -> Response:
    return await _passthrough(
        request,
        context,
        f"/clients/{context.account.company_id}",
        {"page": page, "count": count},
    )


@router.get("/staff")
async def staff(
    request: Request,
    context: DeviceContext = Depends(require_live_account),
) -> Response:
    """`/book_staff` — публичный справочник сотрудников филиала.

    Свой `staff_id` через него больше не ищут: это делает сервер один раз при
    входе. Приложению он нужен для карточек сотрудников.
    """
    return await _passthrough(
        request, context, f"/book_staff/{context.account.company_id}", {}
    )


@router.get("/salary/daily")
async def salary_daily(
    request: Request,
    date_from: str = Query(...),
    date_to: str = Query(...),
    context: DeviceContext = Depends(require_live_account),
) -> Response:
    account = context.account
    return await _passthrough(
        request,
        context,
        f"/company/{account.company_id}/salary/period/staff/daily/{account.staff_id}/",
        {"date_from": date_from, "date_to": date_to},
    )


@router.get("/salary/calculations")
async def salary_calculations(
    request: Request,
    date_from: str = Query(...),
    date_to: str = Query(...),
    context: DeviceContext = Depends(require_live_account),
) -> Response:
    account = context.account
    return await _passthrough(
        request,
        context,
        f"/company/{account.company_id}/salary/payroll/staff/{account.staff_id}/calculation/",
        {"date_from": date_from, "date_to": date_to},
    )


@router.get("/salary/calculations/{calculation_id}")
async def salary_calculation_details(
    request: Request,
    calculation_id: int,
    context: DeviceContext = Depends(require_live_account),
) -> Response:
    account = context.account
    return await _passthrough(
        request,
        context,
        f"/company/{account.company_id}/salary/payroll/staff/{account.staff_id}"
        f"/calculation/{calculation_id}",
        {},
    )
