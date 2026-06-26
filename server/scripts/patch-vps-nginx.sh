#!/usr/bin/env bash
# nginx + Let's Encrypt на VPS для push.neiro.greemlab.ru
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NEIRO_PUSH_PUBLIC_HOST="${NEIRO_PUSH_PUBLIC_HOST:-push.neiro.greemlab.ru}"
VPS_SSH="${NEIRO_PUSH_VPS_SSH:-roster-vps}"
TUNNEL_PORT="${NEIRO_PUSH_TUNNEL_PORT:-18080}"

SITE="/etc/nginx/sites-available/${NEIRO_PUSH_PUBLIC_HOST}"

echo "VPS: ${VPS_SSH}, host: ${NEIRO_PUSH_PUBLIC_HOST}, upstream: 127.0.0.1:${TUNNEL_PORT}"

ssh "${VPS_SSH}" bash -s <<REMOTE
set -euo pipefail
HOST="${NEIRO_PUSH_PUBLIC_HOST}"
PORT="${TUNNEL_PORT}"
SITE="${SITE}"

if [[ ! -f "\${SITE}" ]]; then
  cat > "\${SITE}" <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name \${HOST};

    location / {
        proxy_pass http://127.0.0.1:\${PORT};
        proxy_http_version 1.1;
        proxy_set_header Host \\\$host;
        proxy_set_header X-Real-IP \\\$remote_addr;
        proxy_set_header X-Forwarded-For \\\$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \\\$scheme;
    }
}
EOF
  ln -sf "\${SITE}" "/etc/nginx/sites-enabled/\${HOST}"
  nginx -t
  systemctl reload nginx
  echo "nginx_http_ok"
fi

if [[ ! -f "/etc/letsencrypt/live/\${HOST}/fullchain.pem" ]]; then
  certbot --nginx -d "\${HOST}" --non-interactive --agree-tos -m admin@greemlab.ru
  echo "certbot_ok"
else
  echo "cert_already_exists"
fi

nginx -t
systemctl reload nginx
curl -fsS "https://\${HOST}/health" && echo || echo "health_check_pending"
REMOTE

echo "Done: https://${NEIRO_PUSH_PUBLIC_HOST}/health"
