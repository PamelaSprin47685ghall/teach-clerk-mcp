#!/usr/bin/env bash
set -euo pipefail

export PORT=11089
export PUBLIC_BASE_URL=http://127.0.0.1:11089
export OAUTH_SIGNING_SECRET="$(openssl rand -hex 32)"
export RUN_CLASSROOM_LOOP=1

clojure -M:run &
server_pid=$!
trap 'kill "$server_pid" 2>/dev/null || true' EXIT

for index in $(seq 1 80); do
  if curl --silent --fail "$PUBLIC_BASE_URL/healthz" >/dev/null; then
    python3 scripts/oauth_smoke.py
    exit 0
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "Server stopped before it was ready" >&2
    exit 1
  fi
  sleep 0.25
done

echo "Server did not become ready" >&2
exit 1
