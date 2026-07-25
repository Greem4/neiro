#!/usr/bin/env bash
# SSH-хелпер для deploy/install скриптов neiro-push-events.
set -euo pipefail

pick_ssh_host() {
  if [[ -n "${PI_SSH:-}" ]]; then
    echo "${PI_SSH}"
    return
  fi
  if ssh -o BatchMode=yes -o ConnectTimeout=4 roster-b3 'echo ok' >/dev/null 2>&1; then
    echo "roster-b3"
    return
  fi
  echo "roster-pi-remote"
}

SSH_HOST="$(pick_ssh_host)"

ssh_pi() {
  ssh "${SSH_HOST}" "$@"
}

rsync_pi() {
  rsync -az "$@" "${SSH_HOST}:"
}
