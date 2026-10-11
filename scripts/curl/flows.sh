#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Readable curl walkthrough of the ms-ticket flows against a running stack
# (docker compose up -d --wait). Every step prints the request, the HTTP status and
# the response body, and checks the expected outcome (PASS/FAIL summary at the end).
#
# Usage:
#   scripts/curl/flows.sh                 # every flow, in order
#   scripts/curl/flows.sh purchase cancel # only the named flows
#   scripts/curl/flows.sh --list          # flow names
#
# Flows:
#   events         create / get / list (cursor pagination) / validation / 404
#   purchase       place order (202 + Location) -> RESERVED -> confirm -> SOLD
#   idempotency    replay (same orderId), same key + other body (422), bad/missing key (400)
#   ownership      other customer gets 404 (anti-IDOR), ADMIN gets 200
#   declined       tok_declined -> PAYMENT_DECLINED, tickets released
#   cancel         DELETE reservation -> CANCELLED, tickets released, confirm -> 409
#   complimentary  ADMIN issues courtesy tickets -> COMPLIMENTARY (separate counter)
#   audit          ticket transition history (ADMIN), 403 for customers
#   soldout        409 SOLD_OUT at admission + race lost in the consumer (REJECTED/SOLD_OUT)
#   security       401 without/with a forged token, 403 with the wrong role
#   ratelimit      burst of POSTs from one customer -> 429 + Retry-After
#   sse            availability stream (text/event-stream) while an order flows
#   metrics        actuator health + Prometheus metrics (API, and worker through docker)
#   expiry         hold expiry RESERVED -> EXPIRED, confirm -> 410 (needs docker-compose.demo.yml)
#   payment-error  tok_error -> retries / redelivery -> reconciliation (fast with docker-compose.demo.yml)
#
# Environment:
#   API_URL       default http://localhost:8080
#   AUTH_URL      default http://localhost:${AUTH_MOCK_HOST_PORT:-8090}/default (tokens MUST come
#                 through the host URL: the API validates iss=http://localhost:8090/default)
#   POLL_TIMEOUT  seconds to wait for an asynchronous state (default 60: covers an SQS redelivery while the payment circuit breaker is open)
#
# Requirements: bash, curl. jq is used when present (a sed fallback parses the flat JSON otherwise).
# -----------------------------------------------------------------------------
set -uo pipefail

API_URL="${API_URL:-http://localhost:8080}"
AUTH_URL="${AUTH_URL:-http://localhost:${AUTH_MOCK_HOST_PORT:-8090}/default}"
POLL_TIMEOUT="${POLL_TIMEOUT:-60}"
ALL_FLOWS=(events purchase idempotency ownership declined cancel complimentary audit soldout security
           ratelimit sse metrics expiry payment-error)

HAVE_JQ=false
command -v jq >/dev/null 2>&1 && HAVE_JQ=true

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

PASS=0
FAIL=0
SKIP=0
STATUS=""
BODY=""

# ---------------------------------------------------------------- output helpers
if [[ -t 1 ]]; then
  C_BOLD=$'\e[1m'; C_GREEN=$'\e[32m'; C_RED=$'\e[31m'; C_YELLOW=$'\e[33m'; C_DIM=$'\e[2m'; C_OFF=$'\e[0m'
else
  C_BOLD=""; C_GREEN=""; C_RED=""; C_YELLOW=""; C_DIM=""; C_OFF=""
fi

section() { printf '\n%s== %s ==%s\n' "${C_BOLD}" "$*" "${C_OFF}"; }
note()    { printf '%s# %s%s\n' "${C_DIM}" "$*" "${C_OFF}"; }
pass()    { PASS=$((PASS + 1)); printf '  %sPASS%s %s\n' "${C_GREEN}" "${C_OFF}" "$*"; }
fail()    { FAIL=$((FAIL + 1)); printf '  %sFAIL%s %s\n' "${C_RED}" "${C_OFF}" "$*"; }
skip()    { SKIP=$((SKIP + 1)); printf '  %sSKIP%s %s\n' "${C_YELLOW}" "${C_OFF}" "$*"; }

# expect <description> <expected> <actual>
expect() {
  if [[ "$3" == "$2" ]]; then pass "$1 ($3)"; else fail "$1: expected '$2', got '$3'"; fi
}

# ---------------------------------------------------------------- JSON helpers
# json_get <field> [json] - top-level scalar of a flat JSON object ("" when absent or null).
json_get() {
  local field="$1" json="${2-${BODY}}"
  if ${HAVE_JQ}; then
    printf '%s' "${json}" | jq -r --arg f "${field}" 'if type == "object" then (.[$f] // empty | tostring) else empty end' 2>/dev/null
  else
    printf '%s' "${json}" | sed -n "s/.*\"${field}\":\"\{0,1\}\([^\",}]*\)\"\{0,1\}[,}].*/\1/p" | head -n 1 | sed 's/^null$//'
  fi
}

# first_ticket_id [json] - first element of "ticketIds".
first_ticket_id() {
  local json="${1-${BODY}}"
  if ${HAVE_JQ}; then
    printf '%s' "${json}" | jq -r '.ticketIds[0] // empty'
  else
    printf '%s' "${json}" | sed -n 's/.*"ticketIds":\["\([^"]*\)".*/\1/p'
  fi
}

# first_item_field <field> [json] - field of items[0] of a page.
first_item_field() {
  local field="$1" json="${2-${BODY}}"
  if ${HAVE_JQ}; then
    printf '%s' "${json}" | jq -r --arg f "${field}" '.items[0][$f] // empty | tostring'
  else
    printf '%s' "${json}" | sed -n "s/^{\"items\":\[{[^}]*\"${field}\":\"\{0,1\}\([^\",}]*\).*/\1/p"
  fi
}

urlencode() { printf '%s' "$1" | sed -e 's/%/%25/g' -e 's/+/%2B/g' -e 's|/|%2F|g' -e 's/=/%3D/g'; }

epoch_of() { date -u -d "$1" +%s 2>/dev/null || gdate -u -d "$1" +%s 2>/dev/null || echo ""; }

# ---------------------------------------------------------------- HTTP helpers
new_key() {
  if [[ -r /proc/sys/kernel/random/uuid ]]; then
    cat /proc/sys/kernel/random/uuid
  else
    printf 'flows-%s-%04x%04x%04x' "$(date +%s)" "${RANDOM}" "${RANDOM}" "${RANDOM}"
  fi
}

token() {
  curl -fsS --max-time 10 -X POST "${AUTH_URL}/token" \
    --data-urlencode 'grant_type=client_credentials' \
    --data-urlencode "client_id=$1" \
    --data-urlencode 'client_secret=not-validated-by-the-mock' \
    --data-urlencode 'scope=ms-ticket' |
    sed -n 's/.*"access_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
}

# http <METHOD> <path> [token] [json body] [extra curl args...]
#   Sets STATUS and BODY; response headers are kept in ${WORK_DIR}/headers.
http_quiet() {
  local method="$1" path="$2" tok="${3:-}" body="${4:-}"
  shift 4 2>/dev/null || shift $#
  local args=(-sS --max-time 15 -X "${method}" -o "${WORK_DIR}/body" -D "${WORK_DIR}/headers" -w '%{http_code}')
  [[ -n "${tok}" ]] && args+=(-H "Authorization: Bearer ${tok}")
  [[ -n "${body}" ]] && args+=(-H 'Content-Type: application/json' --data "${body}")
  STATUS="$(curl "${args[@]}" "$@" "${API_URL}${path}")" || STATUS="000"
  BODY="$(cat "${WORK_DIR}/body" 2>/dev/null)"
}

http() {
  local method="$1" path="$2" body="${4:-}"
  printf '  %s> %s %s%s%s\n' "${C_DIM}" "${method}" "${path}" "${body:+  ${body}}" "${C_OFF}"
  http_quiet "$@"
  local shown="${BODY}"
  (( ${#shown} > 400 )) && shown="${shown:0:400}..."
  printf '  < %s %s\n' "${STATUS}" "${shown}"
}

header() { grep -i "^$1:" "${WORK_DIR}/headers" | head -n 1 | sed 's/^[^:]*:[[:space:]]*//' | tr -d '\r'; }

# place_order <eventId> <token> <quantity> [idempotency key]  -> sets ORDER (id) and KEY
place_order() {
  KEY="${4:-$(new_key)}"
  http POST "/api/v1/events/$1/orders" "$2" "{\"quantity\":$3}" -H "Idempotency-Key: ${KEY}"
  ORDER="$(json_get orderId)"
}

# wait_for_order <orderId> <token> <regex over "status/processingStatus"> [timeout seconds]
wait_for_order() {
  local id="$1" tok="$2" want="$3" timeout="${4:-${POLL_TIMEOUT}}" state=""
  local deadline=$((SECONDS + timeout))
  while :; do
    http_quiet GET "/api/v1/orders/${id}" "${tok}"
    state="$(json_get status)/$(json_get processingStatus)"
    if [[ "${state}" =~ ^(${want})$ ]]; then
      pass "order ${id:0:8}... reached ${state} (reason=$(json_get reason))"
      return 0
    fi
    if (( SECONDS >= deadline )); then
      fail "order ${id:0:8}... still ${state} after ${timeout}s (wanted ${want})"
      return 1
    fi
    sleep 0.5
  done
}

# availability <eventId> - prints and returns the counters as "available/reserved/pending/sold/complimentary"
availability() {
  http GET "/api/v1/events/$1/availability"
  AVAIL="$(json_get available)/$(json_get reserved)/$(json_get pendingConfirmation)/$(json_get sold)/$(json_get complimentary)"
  local total=0 part
  for part in $(json_get available) $(json_get reserved) $(json_get pendingConfirmation) $(json_get sold) $(json_get complimentary); do
    total=$((total + part))
  done
  expect "counters add up to totalCapacity (available/reserved/pending/sold/complimentary=${AVAIL})" "$(json_get totalCapacity)" "${total}"
}

# create_event <name> <capacity>  -> sets EVENT
create_event() {
  local date
  date="$(date -u -d '+90 days' +%Y-%m-%dT20:00:00Z 2>/dev/null || echo '2030-01-01T20:00:00Z')"
  http POST /api/v1/events "${ADMIN}" \
    "{\"name\":\"$1\",\"venue\":\"Arena Norte\",\"eventDate\":\"${date}\",\"totalCapacity\":$2,\"ticketPrice\":75.50,\"currency\":\"USD\"}"
  EVENT="$(json_get eventId)"
}

ensure_event() {
  if [[ -z "${EVENT_ID:-}" ]]; then
    create_event "Flows walkthrough" 20
    EVENT_ID="${EVENT}"
  fi
}

# ---------------------------------------------------------------- flows
flow_events() {
  section "Events: create (ADMIN) / get / list with cursor pagination"
  create_event "Flows walkthrough" 20
  expect "POST /events -> 201" 201 "${STATUS}"
  expect "Location header" "/api/v1/events/${EVENT}" "$(header Location)"
  expect "new event is ON_SALE" ON_SALE "$(json_get status)"
  EVENT_ID="${EVENT}"

  http GET "/api/v1/events/${EVENT_ID}"
  expect "GET /events/{id} -> 200" 200 "${STATUS}"
  expect "availableTickets" 20 "$(json_get availableTickets)"

  create_event "Flows walkthrough (second page)" 5
  http GET "/api/v1/events?status=ON_SALE&limit=1"
  expect "list page 1 -> 200" 200 "${STATUS}"
  local first cursor
  first="$(first_item_field eventId)"
  cursor="$(json_get nextCursor)"
  if [[ -n "${cursor}" ]]; then pass "page 1 has a nextCursor"; else fail "page 1 has no nextCursor"; fi
  http GET "/api/v1/events?status=ON_SALE&limit=1&cursor=$(urlencode "${cursor}")"
  expect "list page 2 -> 200" 200 "${STATUS}"
  if [[ "$(first_item_field eventId)" != "${first}" ]]; then pass "page 2 starts after page 1"; else fail "page 2 repeats page 1"; fi

  http GET "/api/v1/events?limit=1&cursor=not-a-cursor"
  expect "malformed cursor -> 400" 400 "${STATUS}"
  http POST /api/v1/events "${ADMIN}" '{"name":"","venue":"X","eventDate":"2020-01-01T00:00:00Z","totalCapacity":0,"ticketPrice":-1,"currency":"usd"}'
  expect "invalid event -> 400 VALIDATION_ERROR" "400 VALIDATION_ERROR" "${STATUS} $(json_get errorCode)"
  http GET "/api/v1/events/00000000-0000-0000-0000-000000000000"
  expect "unknown event -> 404 EVENT_NOT_FOUND" "404 EVENT_NOT_FOUND" "${STATUS} $(json_get errorCode)"
  availability "${EVENT_ID}"
}

flow_purchase() {
  section "Purchase: place order -> RESERVED -> confirm (tok_approved) -> SOLD"
  ensure_event
  place_order "${EVENT_ID}" "${C1}" 2
  expect "POST /events/{id}/orders -> 202" 202 "${STATUS}"
  expect "Location header" "/api/v1/orders/${ORDER}" "$(header Location)"
  expect "accepted order is queued" "AVAILABLE/QUEUED" "$(json_get status)/$(json_get processingStatus)"
  ORDER_ID="${ORDER}"
  ORDER_KEY="${KEY}"

  wait_for_order "${ORDER_ID}" "${C1}" "RESERVED/ACTIVE"
  note "hold expires at $(json_get holdExpiresAt)"
  SOLD_TICKET_ID="$(first_ticket_id)"
  availability "${EVENT_ID}"

  http POST "/api/v1/orders/${ORDER_ID}/confirmation" "${C1}" '{"paymentToken":"tok_approved"}' -H "Idempotency-Key: $(new_key)"
  expect "POST /orders/{id}/confirmation -> 202" 202 "${STATUS}"
  expect "confirmation moves tickets to PENDING_CONFIRMATION" PENDING_CONFIRMATION "$(json_get status)"

  wait_for_order "${ORDER_ID}" "${C1}" "SOLD/COMPLETED"
  availability "${EVENT_ID}"

  http POST "/api/v1/orders/${ORDER_ID}/confirmation" "${C1}" '{"paymentToken":"tok_approved"}' -H "Idempotency-Key: $(new_key)"
  expect "confirming a SOLD order again is a no-op" "202 SOLD" "${STATUS} $(json_get status)"
}

flow_idempotency() {
  section "Idempotency-Key: replay, conflicting body, missing/invalid key"
  ensure_event
  if [[ -z "${ORDER_ID:-}" ]]; then place_order "${EVENT_ID}" "${C1}" 2; ORDER_ID="${ORDER}"; ORDER_KEY="${KEY}"; fi

  place_order "${EVENT_ID}" "${C1}" 2 "${ORDER_KEY}"
  expect "same key + same body -> same orderId" "${ORDER_ID}" "${ORDER}"
  place_order "${EVENT_ID}" "${C1}" 3 "${ORDER_KEY}"
  expect "same key + different body -> 422 IDEMPOTENCY_KEY_REUSED" "422 IDEMPOTENCY_KEY_REUSED" "${STATUS} $(json_get errorCode)"
  http POST "/api/v1/events/${EVENT_ID}/orders" "${C1}" '{"quantity":1}'
  expect "missing key -> 400 INVALID_IDEMPOTENCY_KEY" "400 INVALID_IDEMPOTENCY_KEY" "${STATUS} $(json_get errorCode)"
  http POST "/api/v1/events/${EVENT_ID}/orders" "${C1}" '{"quantity":1}' -H 'Idempotency-Key: bad_key!'
  expect "malformed key -> 400 INVALID_IDEMPOTENCY_KEY" "400 INVALID_IDEMPOTENCY_KEY" "${STATUS} $(json_get errorCode)"
  http POST "/api/v1/events/${EVENT_ID}/orders" "${C1}" '{"quantity":11}' -H "Idempotency-Key: $(new_key)"
  expect "quantity above 10 -> 400 VALIDATION_ERROR" "400 VALIDATION_ERROR" "${STATUS} $(json_get errorCode)"
  note "keys are scoped per customer: customer-2 reusing customer-1's key gets its own order"
  place_order "${EVENT_ID}" "${C2}" 1 "${ORDER_KEY}"
  if [[ "${STATUS}" == 202 && "${ORDER}" != "${ORDER_ID}" ]]; then pass "customer-2 got a different orderId"; else fail "customer-2: ${STATUS} ${ORDER}"; fi
  local other="${ORDER}"
  wait_for_order "${other}" "${C2}" "RESERVED/ACTIVE"
  http DELETE "/api/v1/orders/${other}/reservation" "${C2}"
}

flow_ownership() {
  section "Ownership: anti-IDOR"
  ensure_event
  if [[ -z "${ORDER_ID:-}" ]]; then place_order "${EVENT_ID}" "${C1}" 1; ORDER_ID="${ORDER}"; fi
  http GET "/api/v1/orders/${ORDER_ID}" "${C1}"
  expect "owner reads its order -> 200" 200 "${STATUS}"
  http GET "/api/v1/orders/${ORDER_ID}" "${C2}"
  expect "another customer -> 404 ORDER_NOT_FOUND (no existence leak)" "404 ORDER_NOT_FOUND" "${STATUS} $(json_get errorCode)"
  http POST "/api/v1/orders/${ORDER_ID}/confirmation" "${C2}" '{"paymentToken":"tok_approved"}' -H "Idempotency-Key: $(new_key)"
  expect "another customer cannot confirm -> 404" 404 "${STATUS}"
  http DELETE "/api/v1/orders/${ORDER_ID}/reservation" "${C2}"
  expect "another customer cannot cancel -> 404" 404 "${STATUS}"
  http GET "/api/v1/orders/${ORDER_ID}" "${ADMIN}"
  expect "ADMIN reads any order -> 200" 200 "${STATUS}"
}

flow_declined() {
  section "Payment declined: tok_declined -> PAYMENT_DECLINED, tickets released"
  ensure_event
  place_order "${EVENT_ID}" "${C1}" 1
  local id="${ORDER}"
  wait_for_order "${id}" "${C1}" "RESERVED/ACTIVE"
  availability "${EVENT_ID}"
  local before="${AVAIL%%/*}"
  http POST "/api/v1/orders/${id}/confirmation" "${C1}" '{"paymentToken":"tok_declined"}' -H "Idempotency-Key: $(new_key)"
  expect "confirmation accepted" "202 PENDING_CONFIRMATION" "${STATUS} $(json_get status)"
  wait_for_order "${id}" "${C1}" "AVAILABLE/PAYMENT_DECLINED"
  availability "${EVENT_ID}"
  expect "declined ticket is available again" "$((before + 1))" "${AVAIL%%/*}"
}

flow_cancel() {
  section "Cancel reservation: DELETE -> CANCELLED, tickets released"
  ensure_event
  place_order "${EVENT_ID}" "${C1}" 2
  local id="${ORDER}"
  wait_for_order "${id}" "${C1}" "RESERVED/ACTIVE"
  availability "${EVENT_ID}"
  local before="${AVAIL%%/*}"
  http DELETE "/api/v1/orders/${id}/reservation" "${C1}"
  expect "DELETE /orders/{id}/reservation -> 202 CANCELLED" "202 AVAILABLE/CANCELLED" "${STATUS} $(json_get status)/$(json_get processingStatus)"
  availability "${EVENT_ID}"
  expect "both tickets are available again" "$((before + 2))" "${AVAIL%%/*}"
  http DELETE "/api/v1/orders/${id}/reservation" "${C1}"
  expect "cancelling twice is idempotent" "202 CANCELLED" "${STATUS} $(json_get processingStatus)"
  http POST "/api/v1/orders/${id}/confirmation" "${C1}" '{"paymentToken":"tok_approved"}' -H "Idempotency-Key: $(new_key)"
  expect "confirming a cancelled order -> 409 INVALID_ORDER_STATE" "409 INVALID_ORDER_STATE" "${STATUS} $(json_get errorCode)"
}

flow_complimentary() {
  section "Complimentary tickets (ADMIN): final, not accountable"
  ensure_event
  http POST "/api/v1/events/${EVENT_ID}/complimentary-tickets" "${ADMIN}" '{"beneficiaryId":"vip-guest-1","quantity":2}' -H "Idempotency-Key: $(new_key)"
  expect "POST /complimentary-tickets -> 202" 202 "${STATUS}"
  local id
  id="$(json_get orderId)"
  wait_for_order "${id}" "${ADMIN}" "COMPLIMENTARY/COMPLETED"
  availability "${EVENT_ID}"
  if [[ "$(json_get complimentary)" -ge 2 ]]; then pass "complimentary counter is separate from sold"; else fail "complimentary counter"; fi
  http POST "/api/v1/events/${EVENT_ID}/complimentary-tickets" "${C1}" '{"beneficiaryId":"me","quantity":1}' -H "Idempotency-Key: $(new_key)"
  expect "customer cannot issue complimentary tickets -> 403" 403 "${STATUS}"
}

flow_audit() {
  section "Ticket audit trail (ADMIN)"
  ensure_event
  if [[ -z "${SOLD_TICKET_ID:-}" ]]; then flow_purchase; fi
  http GET "/api/v1/events/${EVENT_ID}/tickets/${SOLD_TICKET_ID}/transitions" "${ADMIN}"
  expect "GET .../tickets/{ticketId}/transitions -> 200" 200 "${STATUS}"
  local reasons
  if ${HAVE_JQ}; then
    reasons="$(printf '%s' "${BODY}" | jq -r '[.[].reason] | join(",")')"
  else
    reasons="$(printf '%s' "${BODY}" | grep -o '"reason":"[A-Z_]*"' | sed 's/"reason":"\(.*\)"/\1/' | paste -sd, -)"
  fi
  expect "sold ticket history" "RESERVATION,CUSTOMER_CONFIRMATION,PAYMENT_APPROVED" "${reasons}"
  http GET "/api/v1/events/${EVENT_ID}/tickets/${SOLD_TICKET_ID}/transitions" "${C1}"
  expect "customer -> 403" 403 "${STATUS}"
}

flow_soldout() {
  section "Sold out: 409 at admission, REJECTED/SOLD_OUT when the race is lost"
  create_event "Tiny venue" 2
  local tiny="${EVENT}"
  place_order "${tiny}" "${C1}" 2
  wait_for_order "${ORDER}" "${C1}" "RESERVED/ACTIVE"
  note "waiting 2.5 s for the 2 s event cache to see availableCount = 0"
  sleep 2.5
  place_order "${tiny}" "${C2}" 1
  expect "admission soft check -> 409 SOLD_OUT" "409 SOLD_OUT" "${STATUS} $(json_get errorCode)"

  create_event "Single seat" 1
  local single="${EVENT}"
  note "two customers race for the last seat: both are admitted (202), the consumer lets exactly one win"
  place_order "${single}" "${C1}" 1
  local first="${ORDER}"
  place_order "${single}" "${C2}" 1
  local second="${ORDER}"
  wait_for_order "${first}" "${C1}" "RESERVED/ACTIVE|AVAILABLE/REJECTED"
  local s1
  s1="$(json_get processingStatus)/$(json_get reason)"
  wait_for_order "${second}" "${C2}" "RESERVED/ACTIVE|AVAILABLE/REJECTED"
  local s2
  s2="$(json_get processingStatus)/$(json_get reason)"
  local outcome
  outcome="$(printf '%s\n%s\n' "${s1}" "${s2}" | sort | paste -sd' ' -)"
  expect "exactly one winner, the other REJECTED with SOLD_OUT" "ACTIVE/ REJECTED/SOLD_OUT" "${outcome}"
  availability "${single}"
  expect "no oversell" "0/1/0/0/0" "${AVAIL}"
}

flow_security() {
  section "Security: 401 / 403"
  ensure_event
  http POST "/api/v1/events/${EVENT_ID}/orders" "" '{"quantity":1}' -H "Idempotency-Key: $(new_key)"
  expect "no token -> 401 UNAUTHORIZED" "401 UNAUTHORIZED" "${STATUS} $(json_get errorCode)"
  http GET "/api/v1/orders/${ORDER_ID:-00000000-0000-0000-0000-000000000000}" "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.Zm9yZ2Vk"
  expect "forged token -> 401" 401 "${STATUS}"
  http POST /api/v1/events "${C1}" '{"name":"x","venue":"y","eventDate":"2030-01-01T00:00:00Z","totalCapacity":1,"ticketPrice":1,"currency":"USD"}'
  expect "CUSTOMER creating an event -> 403 FORBIDDEN" "403 FORBIDDEN" "${STATUS} $(json_get errorCode)"
  http GET "/api/v1/events/${EVENT_ID}/availability"
  expect "public reads need no token -> 200" 200 "${STATUS}"
}

flow_ratelimit() {
  section "Rate limit: 40 parallel POSTs from one customer (bucket 20, refill 20/s)"
  ensure_event
  local urls=()
  # One "-o" per URL: curl applies an output option to a single URL only.
  for _ in $(seq 1 40); do urls+=(-o /dev/null "${API_URL}/api/v1/events/${EVENT_ID}/orders"); done
  printf '  %s> 40 x POST /api/v1/events/%s/orders (no Idempotency-Key, customer-2)%s\n' "${C_DIM}" "${EVENT_ID}" "${C_OFF}"
  curl -s -Z --parallel-max 40 -X POST -H "Authorization: Bearer ${C2}" -H 'Content-Type: application/json' \
    --data '{"quantity":1}' -w '%{http_code} %header{retry-after}\n' "${urls[@]}" >"${WORK_DIR}/burst"
  local limited allowed
  limited="$(grep -c '^429' "${WORK_DIR}/burst")"
  allowed="$(grep -c '^400' "${WORK_DIR}/burst")"
  printf '  < %s x 400 (passed the limiter, rejected for the missing key), %s x 429 (Retry-After: %s s)\n' \
    "${allowed}" "${limited}" "$(grep '^429' "${WORK_DIR}/burst" | head -n 1 | cut -d' ' -f2)"
  if (( limited > 0 )); then pass "burst above the bucket gets 429 RATE_LIMIT_EXCEEDED"; else fail "no 429 in the burst"; fi
  note "waiting 2 s for the bucket to refill"
  sleep 2
}

flow_sse() {
  section "Availability stream (SSE) while an order flows"
  ensure_event
  local stream="${WORK_DIR}/sse"
  printf '  %s> GET /api/v1/events/%s/availability/stream (curl -N, 8 s)%s\n' "${C_DIM}" "${EVENT_ID}" "${C_OFF}"
  curl -sN --max-time 8 -H 'Accept: text/event-stream' "${API_URL}/api/v1/events/${EVENT_ID}/availability/stream" >"${stream}" &
  local sse_pid=$!
  sleep 1.5
  place_order "${EVENT_ID}" "${C1}" 1
  local id="${ORDER}"
  wait_for_order "${id}" "${C1}" "RESERVED/ACTIVE"
  sleep 1.5
  http DELETE "/api/v1/orders/${id}/reservation" "${C1}"
  wait "${sse_pid}" 2>/dev/null
  sed -n 's/^/  | /p' "${stream}"
  local frames
  frames="$(grep -c '^data:' "${stream}")"
  if (( frames >= 3 )); then pass "${frames} availability frames (initial, reserved, released)"; else fail "only ${frames} frames"; fi
}

flow_metrics() {
  section "Actuator: health and Prometheus metrics"
  http GET /actuator/health/readiness
  expect "readiness -> 200" 200 "${STATUS}"
  http_quiet GET /actuator/prometheus
  expect "API /actuator/prometheus -> 200" 200 "${STATUS}"
  printf '%s\n' "${BODY}" | grep -E '^http_server_requests_seconds_count\{.*uri="/api/v1/events/\{eventId\}/orders"' | sed 's/^/  | /' | head -n 5
  # The worker publishes no host port: read its metrics from inside the compose network, through the
  # localstack container (it ships curl; the JRE-only application image does not).
  local probe=""
  command -v docker >/dev/null 2>&1 && probe="$(docker ps -q --filter label=com.docker.compose.project=ms-ticket \
    --filter label=com.docker.compose.service=localstack 2>/dev/null | head -n 1)"
  if [[ -n "${probe}" ]] && docker exec "${probe}" curl -sf http://ticketing-worker:8080/actuator/prometheus \
      >"${WORK_DIR}/worker-metrics" 2>/dev/null; then
    note "worker metrics (fetched inside the compose network)"
    grep -E '^(sqs_messages_total|sqs_messages_in_flight|sweeper_results_total|sweeper_orders_total)' \
      "${WORK_DIR}/worker-metrics" | sed 's/^/  | /' | head -n 20
    if grep -q '^sqs_messages_total' "${WORK_DIR}/worker-metrics"; then pass "worker exports sqs.messages"; else fail "no sqs_messages_total from the worker"; fi
    if grep -q '^sweeper_results_total' "${WORK_DIR}/worker-metrics"; then pass "worker exports sweeper.results"; else fail "no sweeper_results_total from the worker"; fi
  else
    skip "docker / compose project ms-ticket not reachable: worker metrics not checked"
  fi
}

# Hold TTL of the running configuration, in seconds ("" when unknown).
hold_ttl_seconds() {
  local expires updated
  expires="$(epoch_of "$(json_get holdExpiresAt)")"
  updated="$(epoch_of "$(json_get updatedAt)")"
  [[ -n "${expires}" && -n "${updated}" ]] && echo $((expires - updated))
}

flow_expiry() {
  section "Hold expiry: RESERVED -> EXPIRED by the sweeper, confirm -> 410"
  ensure_event
  place_order "${EVENT_ID}" "${C1}" 1
  local id="${ORDER}"
  wait_for_order "${id}" "${C1}" "RESERVED/ACTIVE" || return
  local ttl
  ttl="$(hold_ttl_seconds)"
  if [[ -z "${ttl}" || "${ttl}" -gt 120 ]]; then
    skip "hold TTL is ${ttl:-unknown} s; start the demo override (docker-compose.demo.yml, hold 30 s) to watch it expire"
    http DELETE "/api/v1/orders/${id}/reservation" "${C1}"
    return
  fi
  note "hold TTL ${ttl} s + sweeper interval: waiting for the release"
  wait_for_order "${id}" "${C1}" "AVAILABLE/EXPIRED" $((ttl + 30))
  expect "release reason" HOLD_EXPIRED "$(json_get reason)"
  http POST "/api/v1/orders/${id}/confirmation" "${C1}" '{"paymentToken":"tok_approved"}' -H "Idempotency-Key: $(new_key)"
  expect "confirming after expiry -> 410 HOLD_EXPIRED" "410 HOLD_EXPIRED" "${STATUS} $(json_get errorCode)"
  availability "${EVENT_ID}"
}

flow_payment_error() {
  section "Payment gateway error: tok_error -> retries, redelivery backoff, reconciliation"
  ensure_event
  place_order "${EVENT_ID}" "${C1}" 1
  local id="${ORDER}"
  wait_for_order "${id}" "${C1}" "RESERVED/ACTIVE" || return
  local ttl
  ttl="$(hold_ttl_seconds)"
  http POST "/api/v1/orders/${id}/confirmation" "${C1}" '{"paymentToken":"tok_error"}' -H "Idempotency-Key: $(new_key)"
  expect "confirmation accepted" "202 PENDING_CONFIRMATION" "${STATUS} $(json_get status)"
  note "the worker retries the charge (500), then SQS redelivers with backoff 10 s, 20 s, 40 s... (DLQ after 5 receives)"
  sleep 3
  http GET "/api/v1/orders/${id}" "${C1}"
  expect "order stays PENDING_CONFIRMATION while the gateway fails" PENDING_CONFIRMATION "$(json_get status)"
  if [[ -n "${ttl}" && "${ttl}" -le 120 ]]; then
    note "demo override: the reconciliation sweeper settles it after the confirmation timeout (20 s)"
    wait_for_order "${id}" "${C1}" "AVAILABLE/EXPIRED" 60
    expect "reconciliation reason" CONFIRMATION_TIMEOUT "$(json_get reason)"
  else
    skip "normal config: the reconciliation happens after the 5 min confirmation timeout (order ${id})"
  fi
}

# ---------------------------------------------------------------- main
if [[ "${1:-}" == "--list" ]]; then printf '%s\n' "${ALL_FLOWS[@]}"; exit 0; fi
if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then sed -n '2,38p' "$0" | sed 's/^# \{0,1\}//'; exit 0; fi

flows=("$@")
(( ${#flows[@]} == 0 )) && flows=("${ALL_FLOWS[@]}")

section "Setup"
note "API ${API_URL}, issuer ${AUTH_URL}, jq: ${HAVE_JQ}"
ADMIN="$(token admin)"
C1="$(token customer-1)"
C2="$(token customer-2)"
if [[ -z "${ADMIN}" || -z "${C1}" || -z "${C2}" ]]; then
  echo "could not get tokens from ${AUTH_URL} - is the stack running (docker compose up -d --wait)?" >&2
  exit 1
fi
pass "tokens for admin, customer-1 and customer-2"

for flow in "${flows[@]}"; do
  fn="flow_${flow//-/_}"
  if declare -F "${fn}" >/dev/null; then "${fn}"; else echo "unknown flow: ${flow} (see --list)" >&2; exit 2; fi
done

section "Summary"
printf '  %s passed, %s failed, %s skipped\n' "${PASS}" "${FAIL}" "${SKIP}"
(( FAIL == 0 ))
