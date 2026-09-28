#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Only this Compose project's database is stopped, and always restored.
health() {
  docker compose exec -T processing wget -T 5 -q -O - "http://127.0.0.1:8082/actuator/health/$1"
}
restore_database() {
  docker compose up -d --no-build --wait postgres
}

[[ "$(health readiness)" == '{"status":"UP"}' ]]
[[ "$(health liveness)" == '{"status":"UP"}' ]]
trap restore_database EXIT
docker compose stop postgres
response=$(docker compose exec -T processing wget -T 5 -S -O - http://127.0.0.1:8082/actuator/health/readiness 2>&1) && {
  echo 'Readiness incorrectly accepted an unavailable database.' >&2
  exit 1
}
[[ "$response" == *"503"* ]] || { echo 'Readiness did not return HTTP 503.' >&2; exit 1; }
[[ "$(health liveness)" == '{"status":"UP"}' ]]
restore_database
trap - EXIT
for attempt in {1..20}; do
  if [[ "$(health readiness 2>/dev/null || true)" == '{"status":"UP"}' ]]; then
    echo 'Bootstrap PASS: readiness UP -> HTTP 503 -> UP; liveness remains UP.'
    exit 0
  fi
  sleep 1
done
echo 'Readiness did not recover after database restart.' >&2
exit 1
