#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

run_checked() {
  local name="$1"
  shift
  local log_file="$TMP_DIR/${name}.log"
  if ! "$@" >"$log_file" 2>&1; then
    echo "FAIL step=$name"
    tail -n 80 "$log_file"
    exit 1
  fi
  echo "PASS step=$name"
}

run_checked backend-tests bash -lc "cd '$ROOT_DIR/backend' && env MAVEN_OPTS=-Xss768k mvn test"
run_checked frontend-tests bash -lc "cd '$ROOT_DIR/frontend' && npm test"
run_checked frontend-build bash -lc "cd '$ROOT_DIR/frontend' && npm run build"

ssh racknerd \
  'env REQUEST_COUNT=10 MIN_WORKERS=10 MIN_LLM_PARALLEL=10 MAX_P95_SECONDS=60 MAX_PEAK_MIB=230 MAX_IDLE_MIB=205 MAX_PIDS=40 MAX_RESTART_COUNT=0 bash -s' \
  < "$ROOT_DIR/scripts/performance/remote-resource-capacity-benchmark.sh"

echo "PASS evaluator=ten-way-model-concurrency"
