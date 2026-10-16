#!/usr/bin/env bash
set -euo pipefail

export PORT=11095
export PUBLIC_BASE_URL=http://127.0.0.1:11095
export OAUTH_SIGNING_SECRET="$(openssl rand -hex 32)"
export RUN_MIXED_MODE_LOOP=1

clojure -M:run &
pid=$!
trap 'kill "$pid" 2>/dev/null || true' EXIT

for attempt in $(seq 1 100); do
  if curl --silent --fail "$PUBLIC_BASE_URL/healthz" >/dev/null; then
    python3 scripts/oauth_smoke.py
    exit 0
  fi
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "Classroom JVM stopped" >&2
    exit 1
  fi
  sleep 0.25
done
echo "Classroom JVM did not become ready" >&2
exit 1
