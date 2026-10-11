#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Smoke test of the local infrastructure containers (the application is not needed):
#   1. LocalStack  - the four queues exist with the expected visibility timeout, long
#                    polling, SSE and redrive policy, and a poison message really
#                    lands in the DLQ after maxReceiveCount receives
#   2. DynamoDB    - DynamoDB Local answers ListTables
#   3. Payments    - WireMock scenarios: approved, declined, unknown token, slow, 500,
#                    missing Idempotency-Key, GET charge 404
#   4. Auth        - auth-mock issues JWTs with the expected sub / roles / aud / iss / exp
#
# Usage (from any directory):
#   docker compose up -d --wait dynamodb-local localstack payment-mock auth-mock
#   scripts/infra/verify-infra.sh
# -----------------------------------------------------------------------------
set -euo pipefail
export MSYS_NO_PATHCONV=1 # Git Bash on Windows: do not rewrite /paths in docker arguments

cd "$(dirname "$0")/../.."

# Reads a setting from the environment, then from .env, then falls back to a default.
setting() {
  local key="$1" default="$2" value=""
  value="${!key:-}"
  if [[ -z "${value}" && -f .env ]]; then
    value="$(sed -n "s/^${key}=//p" .env | tail -n 1 | tr -d '\r')"
  fi
  printf '%s' "${value:-${default}}"
}

REGION="$(setting LOCAL_AWS_REGION us-east-1)"
DYNAMODB_URL="http://localhost:$(setting DYNAMODB_HOST_PORT 8000)"
PAYMENT_URL="http://localhost:$(setting PAYMENT_MOCK_HOST_PORT 8089)"
AUTH_PORT="$(setting AUTH_MOCK_HOST_PORT 8090)"
EXPECTED_ISSUER="http://localhost:${AUTH_PORT}/default"
QUEUES=(
  "$(setting SQS_ORDER_RESERVATION_QUEUE order-reservation-queue)"
  "$(setting SQS_ORDER_PAYMENT_QUEUE order-payment-queue)"
)
readonly REGION DYNAMODB_URL PAYMENT_URL AUTH_PORT EXPECTED_ISSUER QUEUES

failures=0
pass() { printf '  [PASS] %s\n' "$*"; }
fail() {
  printf '  [FAIL] %s\n' "$*" >&2
  failures=$((failures + 1))
}
# expect <description> <actual> <extended regex>
expect() {
  if grep -Eq -- "$3" <<<"$2"; then pass "$1"; else fail "$1 -> got: $2"; fi
}

sqs() {
  docker compose exec -T localstack awslocal --region "${REGION}" sqs "$@"
}

# ---------------------------------------------------------------------------
echo "1. LocalStack SQS"
for queue in "${QUEUES[@]}"; do
  dlq="${queue%-queue}-dlq"
  for name in "${queue}" "${dlq}"; do
    if url="$(sqs get-queue-url --queue-name "${name}" --query QueueUrl --output text 2>/dev/null)"; then
      pass "queue ${name} exists (${url//$'\r'/})"
    else
      fail "queue ${name} is missing"
    fi
  done
  queue_url="$(sqs get-queue-url --queue-name "${queue}" --query QueueUrl --output text | tr -d '\r')"
  attributes="$(sqs get-queue-attributes --queue-url "${queue_url}" --attribute-names All \
    --query 'Attributes.[VisibilityTimeout,ReceiveMessageWaitTimeSeconds,SqsManagedSseEnabled,RedrivePolicy]' \
    --output text | tr -d '\r')"
  expect "${queue}: VisibilityTimeout=60, ReceiveMessageWaitTimeSeconds=20, SqsManagedSseEnabled=true" \
    "${attributes}" $'^60\t20\t[Tt]rue\t'
  expect "${queue}: RedrivePolicy -> ${dlq}, maxReceiveCount=5" \
    "${attributes}" "\"deadLetterTargetArn\": *\"arn:aws:sqs:${REGION}:[0-9]+:${dlq}\".*\"maxReceiveCount\": *\"?5\"?"
  dlq_url="$(sqs get-queue-url --queue-name "${dlq}" --query QueueUrl --output text | tr -d '\r')"
  dlq_attributes="$(sqs get-queue-attributes --queue-url "${dlq_url}" --attribute-names All \
    --query 'Attributes.[MessageRetentionPeriod,SqsManagedSseEnabled,RedriveAllowPolicy]' --output text | tr -d '\r')"
  expect "${dlq}: 14-day retention, SSE, redrive allowed only from ${queue}" \
    "${dlq_attributes}" $'^1209600\t[Tt]rue\t.*"byQueue".*:'"${queue}\""
done

# Poison-message drill on the payment queue: receive it 5 times without deleting it
# (visibility timeout 0 makes it visible again at once); the next receive moves it to the DLQ.
payment_queue="${QUEUES[1]}"
payment_dlq="${payment_queue%-queue}-dlq"
payment_queue_url="$(sqs get-queue-url --queue-name "${payment_queue}" --query QueueUrl --output text | tr -d '\r')"
payment_dlq_url="$(sqs get-queue-url --queue-name "${payment_dlq}" --query QueueUrl --output text | tr -d '\r')"
sqs send-message --queue-url "${payment_queue_url}" --message-body '{"poison":true}' >/dev/null
for _ in 1 2 3 4 5 6; do
  sqs receive-message --queue-url "${payment_queue_url}" --visibility-timeout 0 --wait-time-seconds 0 >/dev/null
done
dead_letter="$(sqs receive-message --queue-url "${payment_dlq_url}" --wait-time-seconds 5 \
  --attribute-names ApproximateReceiveCount \
  --query 'Messages[0].[Body,ReceiptHandle]' --output text | tr -d '\r')"
expect "poison message redriven to ${payment_dlq} after 5 receives" "${dead_letter}" '^\{"poison":true\}'
if [[ "${dead_letter}" == *$'\t'* ]]; then
  sqs delete-message --queue-url "${payment_dlq_url}" --receipt-handle "${dead_letter#*$'\t'}" >/dev/null
fi

# ---------------------------------------------------------------------------
echo "2. DynamoDB Local"
tables="$(curl -sS --max-time 5 -X POST "${DYNAMODB_URL}" \
  -H 'Content-Type: application/x-amz-json-1.0' \
  -H 'X-Amz-Target: DynamoDB_20120810.ListTables' \
  -H "Authorization: AWS4-HMAC-SHA256 Credential=local/20260101/${REGION}/dynamodb/aws4_request, SignedHeaders=host, Signature=0" \
  -d '{}' || true)"
expect "ListTables answers on ${DYNAMODB_URL}" "${tables}" '"TableNames"'

# ---------------------------------------------------------------------------
echo "3. Payment gateway mock (WireMock)"
# charge <token> [extra curl args...] -> prints "<http status>\n<headers+body>"
charge() {
  local token="$1"
  shift
  curl -sS -i --max-time 10 -X POST "${PAYMENT_URL}/v1/charges" \
    -H 'Content-Type: application/json' "$@" \
    -w '\n%{http_code}' \
    -d "{\"orderId\":\"6f1c2a4e-0000-4000-8000-000000000001\",\"customerId\":\"customer-1\",\"amount\":120.50,\"currency\":\"USD\",\"paymentToken\":\"${token}\"}" |
    tr -d '\r'
}
key_header=(-H 'Idempotency-Key: 6f1c2a4e-0000-4000-8000-000000000001')

response="$(charge tok_approved_visa "${key_header[@]}")"
expect "tok_approved -> 201" "${response##*$'\n'}" '^201$'
expect "tok_approved -> APPROVED with pay_ id and null declineReason" "$(tr -d ' \n' <<<"${response}")" \
  '"paymentId":"pay_[A-Za-z0-9]{24}","status":"APPROVED","declineReason":null'
expect "tok_approved -> echoes orderId in the X-Order-Id header" "${response}" \
  'X-Order-Id: 6f1c2a4e-0000-4000-8000-000000000001'
expect "tok_approved -> echoes Idempotency-Key header" "${response}" \
  'Idempotency-Key: 6f1c2a4e-0000-4000-8000-000000000001'

response="$(charge tok_declined "${key_header[@]}")"
expect "tok_declined -> 201 DECLINED / INSUFFICIENT_FUNDS" "${response##*$'\n'} $(tr -d ' \n' <<<"${response}")" \
  '^201 .*"status":"DECLINED","declineReason":"INSUFFICIENT_FUNDS"'

response="$(charge tok_unknown "${key_header[@]}")"
expect "unknown token -> 201 DECLINED / INVALID_PAYMENT_TOKEN" "${response##*$'\n'} $(tr -d ' \n' <<<"${response}")" \
  '^201 .*"status":"DECLINED","declineReason":"INVALID_PAYMENT_TOKEN"'

response="$(charge tok_error "${key_header[@]}")"
expect "tok_error -> 500" "${response##*$'\n'}" '^500$'

response="$(charge tok_approved)"
expect "missing Idempotency-Key -> 400 MISSING_IDEMPOTENCY_KEY" "${response##*$'\n'} $(tr -d ' \n' <<<"${response}")" \
  '^400 .*"error":"MISSING_IDEMPOTENCY_KEY"'

started=${SECONDS}
response="$(charge tok_slow "${key_header[@]}")"
elapsed=$((SECONDS - started))
expect "tok_slow -> 201 APPROVED after >= 5 s (took ${elapsed}s)" "${response##*$'\n'} ${elapsed}" '^201 ([5-9]|[1-9][0-9])$'

response="$(curl -sS --max-time 5 -w '\n%{http_code}' "${PAYMENT_URL}/v1/charges/6f1c2a4e-0000-4000-8000-000000000001" | tr -d '\r')"
expect "GET /v1/charges/{key} -> 404 CHARGE_NOT_FOUND" "${response##*$'\n'} $(tr -d ' \n' <<<"${response}")" \
  '^404 .*"error":"CHARGE_NOT_FOUND"'

# ---------------------------------------------------------------------------
echo "4. Auth mock (mock-oauth2-server)"
jwt_payload() {
  local payload
  payload="$(cut -d. -f2 <<<"$1" | tr '_-' '/+')"
  case $((${#payload} % 4)) in
    2) payload="${payload}==" ;;
    3) payload="${payload}=" ;;
  esac
  base64 -d <<<"${payload}" 2>/dev/null
}
# expect_token <client_id> <role>
expect_token() {
  local token claims iat exp
  if ! token="$(AUTH_MOCK_HOST_PORT="${AUTH_PORT}" scripts/infra/get-token.sh "$1")"; then
    fail "token for $1 could not be issued"
    return
  fi
  claims="$(jwt_payload "${token}" | tr -d ' \n')"
  expect "$1: sub=$1" "${claims}" "\"sub\":\"$1\""
  expect "$1: roles=[$2]" "${claims}" "\"roles\":\[\"$2\"\]"
  expect "$1: aud=ms-ticket" "${claims}" '"aud":(\["ms-ticket"\]|"ms-ticket")'
  expect "$1: iss=${EXPECTED_ISSUER}" "${claims}" "\"iss\":\"${EXPECTED_ISSUER}\""
  iat="$(sed -n 's/.*"iat":\([0-9]*\).*/\1/p' <<<"${claims}")"
  exp="$(sed -n 's/.*"exp":\([0-9]*\).*/\1/p' <<<"${claims}")"
  expect "$1: lifetime 3600 s" "$((exp - iat))" '^3600$'
}
expect_token customer-1 CUSTOMER
expect_token customer-2 CUSTOMER
expect_token admin ADMIN
# The app fetches the signing keys through the service name, from another container.
jwks="$(docker compose exec -T payment-mock curl -fsS --max-time 5 http://auth-mock:8080/default/jwks | tr -d '\r\n ')"
expect "JWKS reachable from the compose network at http://auth-mock:8080/default/jwks" "${jwks}" '"keys":\[\{'

# ---------------------------------------------------------------------------
echo
if ((failures > 0)); then
  echo "${failures} check(s) failed" >&2
  exit 1
fi
echo "all infrastructure checks passed"
