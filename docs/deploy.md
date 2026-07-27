# Деплой neiro-push-events

Проверено на живом стенде 27.07.2026.

## Коротко

```bash
./neiro-push-events/scripts/deploy.sh
```

Одна команда, из любой сети. Скрипт сам выберет, как достучаться до Pi, зальёт
код, пересоберёт контейнер, поднимет туннель, если тот лёг, и проверит публичный
адрес. Если в конце написано `ОК` — деплой удался.

Ничего больше запускать не надо: ни Caddy патчить, ни nginx трогать.

## Как устроен публичный маршрут

Это главное, что стоит понять один раз — почти все прошлые непонятки были
отсюда.

```
https://push.neiro.greemlab.ru/v2/*
        │
        ▼  DNS → 176.12.65.86 (VPS, не Pi)
   ┌─────────────────────────────────┐
   │ VPS: nginx, TLS от Certbot      │
   │   location /v2/                 │
   │     proxy_pass 127.0.0.1:18082/ │  ← слеш в конце срезает префикс /v2
   └─────────────────────────────────┘
        │
        ▼  reverse SSH-туннель: VPS:18082 → Pi:8011
   ┌─────────────────────────────────┐
   │ Pi: neiro-push-events-tunnel    │  systemd --user юнит
   └─────────────────────────────────┘
        │
        ▼
   ┌─────────────────────────────────┐
   │ Pi: контейнер на 127.0.0.1:8011 │
   └─────────────────────────────────┘
```

Три следствия, из которых растут все грабли:

1. **Приложение не знает про `/v2`.** Префикс срезает nginx, до контейнера
   запрос доходит как `/dashboard`, `/health`. Поэтому в HTML-шаблонах все
   ссылки и `action` форм — **относительные**. Абсолютный `/dashboard/login`
   уведёт браузер на корень домена, мимо `/v2`, на чужой сервис.
2. **Туннель — единственное звено между VPS и Pi.** Контейнер может быть жив, а
   публичный адрес молчать. `deploy.sh` это проверяет отдельно.
3. **Caddy на Pi в маршруте не участвует.** См. ниже.

## Про Caddy: почему его тут нет

На Pi крутится Caddy, и в его `~/server/caddy/Caddyfile` есть блок:

```
http://push.neiro.greemlab.ru {
    handle_path /v2/* {
        reverse_proxy neiro-push-events:8011
    }
    ...
}
```

Он рабочий: если постучаться прямо в Pi на порт 80 с нужным `Host`, ответит
сервис. Туда же смотрит cloudflared (`push.neiro.greemlab.ru` →
`http://127.0.0.1:80`).

**Но публичный DNS указывает на VPS, а не на Cloudflare.** Значит, этот путь
спящий: настроен, но трафика по нему нет.

Раньше `deploy.sh` на каждый деплой патчил Caddyfile и перезапускал Caddy —
работа впустую, да ещё и трогала общий для всех проектов Caddy. Отсюда взялась
временная копия `deploy-no-caddy.sh` и путаница «а каким скриптом деплоить».
Теперь шага с Caddy нет, а копия удалена: `deploy.sh` один и делает как надо.

Если когда-нибудь понадобится перевести домен на Cloudflare-путь — достаточно
переключить DNS: конфиг Caddy для этого уже готов.

## Откуда деплоить

`scripts/_ssh.sh` выбирает хост сам:

| Где вы                  | Хост               | Как идёт                                   |
|-------------------------|--------------------|--------------------------------------------|
| Дома, в одной сети с Pi | `roster-b3`        | Напрямую на `192.168.31.96`                |
| Откуда угодно ещё       | `roster-pi-remote` | `ProxyJump` через VPS на проброшенный порт |

Проверка — «отвечает ли `roster-b3` за 4 секунды»; если нет, берётся удалённый
хост. Обычно об этом можно не думать. Принудительно:

```bash
PI_SSH=roster-pi-remote ./neiro-push-events/scripts/deploy.sh
```

## Что делает deploy.sh по шагам

1. `rsync` кода на Pi в `~/neiro-push-events` (без `.env`, `secrets/`, `data/`,
   `tests/` — секреты и база остаются на месте).
2. При первом деплое генерирует `.env` со свежими ключами (`API_KEY`,
   `ADMIN_API_KEY`, `TOKEN_ENCRYPTION_KEY`) и правами `600`. Существующий `.env`
   не трогает никогда.
3. Подтягивает ключ FCM из соседнего `~/neiro-push`, если своего ещё нет.
   Предупреждает, если ключа нет вообще — без него пуши не уходят.
4. `docker compose up -d --build`.
5. Проверяет туннель и поднимает, если тот не активен.
6. Ждёт ответа контейнера на `127.0.0.1:8011/health`.
7. Проверяет публичный `https://.../v2/health`. Не ответил — выходит с ошибкой.

## Если публичный адрес молчит

Идти сверху вниз, первая же неудача и есть причина.

```bash
# 1. Жив ли контейнер
ssh roster-b3 'docker ps | grep neiro-push-events'
ssh roster-b3 'curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Authorization: Bearer $(grep ^ADMIN_API_KEY= ~/neiro-push-events/.env | cut -d= -f2-)" \
  http://127.0.0.1:8011/health'      # ждём 200

# 2. Жив ли туннель
ssh roster-b3 'systemctl --user status neiro-push-events-tunnel.service --no-pager'
ssh roster-b3 'systemctl --user restart neiro-push-events-tunnel.service'

# 3. Доходит ли туннель до VPS
ssh roster-vps 'curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:18082/health'

# 4. Цел ли nginx на VPS
ssh roster-vps 'nginx -t && grep -A3 "location /v2/" \
  /etc/nginx/sites-available/push.neiro.greemlab.ru'
```

Если сломался маршрут в nginx — восстановить:
`scripts/patch-vps-nginx-v2.sh` (идемпотентный).
Если развалился туннель — переставить: `scripts/install-tunnel.sh`.

## Логи

```bash
./neiro-push-events/scripts/logs.sh              # хвост
./neiro-push-events/scripts/logs.sh --errors     # только warning и error
```

## Локальная разработка

Деплой для этого не нужен:

```bash
./neiro-push-events/scripts/dev.sh           # поднять локально
./neiro-push-events/scripts/dev.sh --reset   # пересоздать тестовые данные
```

Дашборд — `http://127.0.0.1:8011/dashboard`. Код и шаблоны подхватываются на
лету, пересобирать контейнер не нужно.

## Резервные копии

```bash
./neiro-push-events/scripts/backup.sh    # забрать базу с Pi
./neiro-push-events/scripts/restore.sh   # вернуть обратно
```
