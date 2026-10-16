#!/usr/bin/env bash
set -euo pipefail

export PORT=11093
export PUBLIC_BASE_URL=http://127.0.0.1:11093
export OAUTH_SIGNING_SECRET="$(openssl rand -hex 32)"
# Test a 1.5-second timeout without making the test suite wait 20 seconds.
export STUDENT_REPLY_TIMEOUT_MS=1500
export RUN_TIMEOUT_LOOP=1

clojure -M:run &
pid=$!
trap 'kill "$pid" 2>/dev/null || true' EXIT

for attempt in $(seq 1 100); do
  if curl --silent --fail "$PUBLIC_BASE_URL/healthz" >/dev/null; then
    python3 scripts/oauth_smoke.py
    exit 0
  fi
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "JVM stopped before startup" >&2
    exit 1
  fi
  sleep 0.25
done
echo "JVM did not start" >&2
exit 1
