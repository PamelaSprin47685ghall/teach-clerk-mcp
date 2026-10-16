#!/usr/bin/env bash
set -euo pipefail

export PORT=11094
export PUBLIC_BASE_URL=http://127.0.0.1:11094
# This secret keeps OAuth available for clients that choose it.
# The anonymous smoke client never uses the secret or authenticates.
export OAUTH_SIGNING_SECRET="$(openssl rand -hex 32)"

clojure -M:run &
server_pid=$!
trap 'kill "$server_pid" 2>/dev/null || true' EXIT

for attempt in $(seq 1 100); do
  if curl --silent --fail "$PUBLIC_BASE_URL/healthz" >/dev/null; then
    python3 scripts/anonymous_smoke.py
    exit 0
  fi
  if ! kill -0 "$server_pid" 2>/dev/null; then
    echo "Classroom JVM stopped" >&2
    exit 1
  fi
  sleep 0.25
done
echo "Classroom JVM did not become ready" >&2
exit 1
