#!/usr/bin/env bash
set -euo pipefail

REQUEST_COUNT="${REQUEST_COUNT:-10}"
MIN_WORKERS="${MIN_WORKERS:-8}"
MIN_LLM_PARALLEL="${MIN_LLM_PARALLEL:-8}"
MAX_P95_SECONDS="${MAX_P95_SECONDS:-75}"
MAX_PEAK_MIB="${MAX_PEAK_MIB:-320}"
MAX_IDLE_MIB="${MAX_IDLE_MIB:-205}"
MAX_PIDS="${MAX_PIDS:-60}"
MAX_RESTART_COUNT="${MAX_RESTART_COUNT:-0}"

cd /opt/sql-tuner-compose
set -a
. ./.env
set +a

BASE_URL="https://sql.pazhaug.info"
TMP_DIR="$(mktemp -d)"
STATS_PID=""

cleanup() {
  if [ -n "$STATS_PID" ]; then
    kill "$STATS_PID" 2>/dev/null || true
    wait "$STATS_PID" 2>/dev/null || true
  fi
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

COOKIE_FILE="$TMP_DIR/admin.cookie"
curl -fsS -c "$COOKIE_FILE" "$BASE_URL/api/auth/csrf" > "$TMP_DIR/csrf-before-login.json"
LOGIN_TOKEN="$(jq -r '.data.token' "$TMP_DIR/csrf-before-login.json")"
LOGIN_PAYLOAD="$(jq -nc --arg username admin --arg password "$SQL_TUNER_ADMIN_PASSWORD" '{username:$username,password:$password}')"
LOGIN_STATUS="$(curl -sS -o "$TMP_DIR/login.json" -w '%{http_code}' \
  -b "$COOKIE_FILE" -c "$COOKIE_FILE" \
  -H "X-XSRF-TOKEN: $LOGIN_TOKEN" \
  -H 'Content-Type: application/json' \
  --data "$LOGIN_PAYLOAD" \
  "$BASE_URL/api/auth/login")"
if [ "$LOGIN_STATUS" != "200" ] || [ "$(jq -r '.data.role // ""' "$TMP_DIR/login.json")" != "ADMIN" ]; then
  echo "FAIL admin-login status=$LOGIN_STATUS"
  exit 1
fi

# 第一次受保护请求会触发 Spring Security Session ID 轮换，必须保存新 Cookie。
WARM_STATUS="$(curl -sS -o "$TMP_DIR/model-config.json" -w '%{http_code}' \
  -b "$COOKIE_FILE" -c "$COOKIE_FILE" \
  "$BASE_URL/api/admin/model-config")"
if [ "$WARM_STATUS" != "200" ]; then
  echo "FAIL admin-session-warmup status=$WARM_STATUS"
  exit 1
fi

curl -fsS -b "$COOKIE_FILE" -c "$COOKIE_FILE" "$BASE_URL/api/auth/csrf" > "$TMP_DIR/csrf.json"
CSRF_TOKEN="$(jq -r '.data.token' "$TMP_DIR/csrf.json")"

monitor_stats() {
  while true; do
    docker stats --no-stream --format '{{.MemUsage}}' sql-tuner >> "$TMP_DIR/stats.log" 2>/dev/null
    sleep 1
  done
}

: > "$TMP_DIR/stats.log"
monitor_stats &
STATS_PID="$!"

REQUEST_PIDS=()
for index in $(seq 1 "$REQUEST_COUNT"); do
  (
    PROMPT="性能评估编号 ${index}。针对虚构的 OceanBase MySQL 查询 SELECT order_id FROM orders WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 20，只返回三条简短且有条件约束的调优建议。"
    PAYLOAD="$(jq -nc --arg prompt "$PROMPT" '{userPrompt:$prompt,deepAnalysis:false}')"
    curl -sS -o "$TMP_DIR/response-${index}.json" \
      -w '%{http_code}\t%{time_total}\n' \
      -b "$COOKIE_FILE" \
      -H "X-XSRF-TOKEN: $CSRF_TOKEN" \
      -H 'Content-Type: application/json' \
      --data "$PAYLOAD" \
      "$BASE_URL/api/admin/model-config/preview" > "$TMP_DIR/result-${index}.tsv"
  ) &
  REQUEST_PIDS+=("$!")
done
for request_pid in "${REQUEST_PIDS[@]}"; do
  wait "$request_pid"
done

kill "$STATS_PID" 2>/dev/null || true
wait "$STATS_PID" 2>/dev/null || true
STATS_PID=""

cat "$TMP_DIR"/result-*.tsv > "$TMP_DIR/results.tsv"
awk '{print $2}' "$TMP_DIR/results.tsv" | sort -n > "$TMP_DIR/times.txt"

SUCCESS_COUNT=0
for index in $(seq 1 "$REQUEST_COUNT"); do
  HTTP_STATUS="$(awk '{print $1}' "$TMP_DIR/result-${index}.tsv")"
  MODEL_SUCCESS="$(jq -r '.data.success // false' "$TMP_DIR/response-${index}.json")"
  MODEL_MOCK="$(jq -r 'if .data.mock == null then true else .data.mock end' "$TMP_DIR/response-${index}.json")"
  if [ "$HTTP_STATUS" = "200" ] && [ "$MODEL_SUCCESS" = "true" ] && [ "$MODEL_MOCK" = "false" ]; then
    SUCCESS_COUNT=$((SUCCESS_COUNT + 1))
  else
    FAILURE_MESSAGE="$(jq -r '.data.message // .message // "unknown"' "$TMP_DIR/response-${index}.json" \
      | tr '\n' ' ' \
      | cut -c 1-240)"
    echo "DETAIL index=$index http=$HTTP_STATUS success=$MODEL_SUCCESS mock=$MODEL_MOCK message=$FAILURE_MESSAGE"
  fi
done

P50_INDEX=$(( (REQUEST_COUNT + 1) / 2 ))
P95_INDEX=$(( (REQUEST_COUNT * 95 + 99) / 100 ))
P50_SECONDS="$(sed -n "${P50_INDEX}p" "$TMP_DIR/times.txt")"
P95_SECONDS="$(sed -n "${P95_INDEX}p" "$TMP_DIR/times.txt")"

PEAK_MIB="$(awk '
  function mib(value) {
    if (value ~ /GiB$/) { sub(/GiB$/, "", value); return value * 1024 }
    if (value ~ /MiB$/) { sub(/MiB$/, "", value); return value }
    if (value ~ /KiB$/) { sub(/KiB$/, "", value); return value / 1024 }
    return 0
  }
  { current = mib($1); if (current > peak) peak = current }
  END { printf "%.3f", peak }
' "$TMP_DIR/stats.log")"

sleep 3
IDLE_STATS="$(docker stats --no-stream --format '{{.MemUsage}} {{.PIDs}}' sql-tuner)"
IDLE_MEMORY="$(printf '%s\n' "$IDLE_STATS" | awk '{print $1}')"
IDLE_MIB="$(awk -v value="$IDLE_MEMORY" 'BEGIN {
  if (value ~ /GiB$/) { sub(/GiB$/, "", value); printf "%.3f", value * 1024; exit }
  if (value ~ /MiB$/) { sub(/MiB$/, "", value); printf "%.3f", value; exit }
  if (value ~ /KiB$/) { sub(/KiB$/, "", value); printf "%.3f", value / 1024; exit }
  print "0.000"
}')"
PIDS="$(printf '%s\n' "$IDLE_STATS" | awk '{print $4}')"
RESTART_COUNT="$(docker inspect --format '{{.RestartCount}}' sql-tuner)"

READINESS_STATUS="$(curl -sS -o "$TMP_DIR/readiness.json" -w '%{http_code}' "$BASE_URL/api/health/ready")"
READINESS_VALUE="$(jq -r '.data.status // "DOWN"' "$TMP_DIR/readiness.json")"
WORKERS="${SQL_TUNER_WORKERS:-4}"
LLM_PARALLEL="${SQL_TUNER_LLM_MAX_PARALLEL:-4}"

echo "RESULT requests=$REQUEST_COUNT success=$SUCCESS_COUNT p50_seconds=$P50_SECONDS p95_seconds=$P95_SECONDS peak_mib=$PEAK_MIB idle_mib=$IDLE_MIB pids=$PIDS restart_count=$RESTART_COUNT workers=$WORKERS llm_parallel=$LLM_PARALLEL readiness_http=$READINESS_STATUS readiness=$READINESS_VALUE"

FAILED=0
[ "$SUCCESS_COUNT" -eq "$REQUEST_COUNT" ] || FAILED=1
[ "$WORKERS" -ge "$MIN_WORKERS" ] || FAILED=1
[ "$LLM_PARALLEL" -ge "$MIN_LLM_PARALLEL" ] || FAILED=1
[ "$PIDS" -le "$MAX_PIDS" ] || FAILED=1
[ "$RESTART_COUNT" -le "$MAX_RESTART_COUNT" ] || FAILED=1
[ "$READINESS_STATUS" = "200" ] || FAILED=1
[ "$READINESS_VALUE" = "UP" ] || FAILED=1
awk -v value="$P95_SECONDS" -v limit="$MAX_P95_SECONDS" 'BEGIN { exit !(value <= limit) }' || FAILED=1
awk -v value="$PEAK_MIB" -v limit="$MAX_PEAK_MIB" 'BEGIN { exit !(value <= limit) }' || FAILED=1
awk -v value="$IDLE_MIB" -v limit="$MAX_IDLE_MIB" 'BEGIN { exit !(value <= limit) }' || FAILED=1

if [ "$FAILED" -ne 0 ]; then
  echo "FAIL evaluator-contract"
  exit 1
fi

echo "PASS evaluator-contract"
