#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ -f .env ]] || { echo 'Configure .env for the local PostgreSQL.' >&2; exit 1; }
source .env
export PROCESSING_TEST_DB_URL="${DB_URL:-jdbc:postgresql://localhost:${POSTGRES_PORT:-5434}/fiapx_processing}"
export PROCESSING_TEST_DB_USERNAME="${DB_USERNAME:-fiapx_processing}"
export PROCESSING_TEST_DB_PASSWORD="${DB_PASSWORD:?Define DB_PASSWORD in .env}"
echo 'Testing PostgreSQL with isolated schemas; no S3 or SQS calls.'
bash ./mvnw -B -ntp -Ppostgres-integration clean verify
