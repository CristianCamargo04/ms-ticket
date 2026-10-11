#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# LocalStack READY hook - mounted into /etc/localstack/init/ready.d/ by
# docker-compose.yml. Creates the order queues and their dead-letter queues with
# the same settings as infra/terraform (modules/sqs), so local behaviour matches AWS:
#
#   main queue : long polling 20 s, visibility timeout 60 s, 4-day retention,
#                SSE-SQS, redrive to its DLQ after 5 receives
#   DLQ        : 14-day retention, SSE-SQS, redrive-allow policy restricted to
#                its own source queue
#
# Idempotent: an existing queue is reconciled with SetQueueAttributes instead of
# failing, so the hook can be re-run at any time:
#   docker compose exec localstack /etc/localstack/init/ready.d/10-init-sqs.sh
#
# Queue names must match the aws.sqs.* defaults of the application
# ("order-reservation-queue", "order-payment-queue"). DLQ = "<name minus -queue>-dlq".
# -----------------------------------------------------------------------------
set -euo pipefail

readonly REGION="${AWS_DEFAULT_REGION:-us-east-1}"
readonly MAX_RECEIVE_COUNT="${SQS_MAX_RECEIVE_COUNT:-5}"
readonly VISIBILITY_TIMEOUT_SECONDS="${SQS_VISIBILITY_TIMEOUT_SECONDS:-60}"
readonly RECEIVE_WAIT_TIME_SECONDS="${SQS_RECEIVE_WAIT_TIME_SECONDS:-20}"
readonly MESSAGE_RETENTION_SECONDS="${SQS_MESSAGE_RETENTION_SECONDS:-345600}" # 4 days
readonly DLQ_RETENTION_SECONDS="${SQS_DLQ_RETENTION_SECONDS:-1209600}"        # 14 days (SQS max)
readonly QUEUES=(
  "${SQS_ORDER_RESERVATION_QUEUE:-order-reservation-queue}"
  "${SQS_ORDER_PAYMENT_QUEUE:-order-payment-queue}"
)

log() {
  printf '[init-sqs] %s\n' "$*" >&2
}

sqs() {
  awslocal --region "${REGION}" sqs "$@"
}

# Escapes a JSON document so it can be embedded as a string value of another JSON document
# (SQS expects RedrivePolicy / RedriveAllowPolicy as JSON-encoded strings).
json_string() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  printf '"%s"' "${value}"
}

dlq_name_for() {
  printf '%s-dlq' "${1%-queue}"
}

# Creates the queue or, when it already exists, converges its attributes. Prints the queue URL.
ensure_queue() {
  local name="$1" attributes="$2" url
  if url="$(sqs get-queue-url --queue-name "${name}" --query QueueUrl --output text 2>/dev/null)"; then
    sqs set-queue-attributes --queue-url "${url}" --attributes "${attributes}"
    log "reconciled ${name}"
  else
    url="$(sqs create-queue --queue-name "${name}" --attributes "${attributes}" --query QueueUrl --output text)"
    log "created ${name}"
  fi
  printf '%s' "${url}"
}

queue_arn() {
  sqs get-queue-attributes --queue-url "$1" --attribute-names QueueArn --query Attributes.QueueArn --output text
}

provision() {
  local queue="$1" dlq dlq_url dlq_arn redrive_policy queue_url queue_arn_value redrive_allow_policy
  dlq="$(dlq_name_for "${queue}")"

  dlq_url="$(ensure_queue "${dlq}" "$(printf '{"MessageRetentionPeriod":"%s","SqsManagedSseEnabled":"true"}' \
    "${DLQ_RETENTION_SECONDS}")")"
  dlq_arn="$(queue_arn "${dlq_url}")"

  redrive_policy="$(printf '{"deadLetterTargetArn":"%s","maxReceiveCount":%s}' "${dlq_arn}" "${MAX_RECEIVE_COUNT}")"
  queue_url="$(ensure_queue "${queue}" "$(printf \
    '{"VisibilityTimeout":"%s","ReceiveMessageWaitTimeSeconds":"%s","MessageRetentionPeriod":"%s","SqsManagedSseEnabled":"true","RedrivePolicy":%s}' \
    "${VISIBILITY_TIMEOUT_SECONDS}" "${RECEIVE_WAIT_TIME_SECONDS}" "${MESSAGE_RETENTION_SECONDS}" \
    "$(json_string "${redrive_policy}")")")"
  queue_arn_value="$(queue_arn "${queue_url}")"

  # Only the matching source queue may move messages into this DLQ (least privilege).
  redrive_allow_policy="$(printf '{"redrivePermission":"byQueue","sourceQueueArns":["%s"]}' "${queue_arn_value}")"
  sqs set-queue-attributes --queue-url "${dlq_url}" \
    --attributes "$(printf '{"RedriveAllowPolicy":%s}' "$(json_string "${redrive_allow_policy}")")"

  log "${queue} -> ${dlq} (maxReceiveCount=${MAX_RECEIVE_COUNT}, visibility=${VISIBILITY_TIMEOUT_SECONDS}s, wait=${RECEIVE_WAIT_TIME_SECONDS}s)"
}

log "provisioning SQS queues in ${REGION}"
for queue in "${QUEUES[@]}"; do
  provision "${queue}"
done
log "done"
