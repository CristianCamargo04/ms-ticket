#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# LocalStack healthcheck (mounted read-only by docker-compose.yml).
#
# "Healthy" means more than "the gateway answers": the READY init hooks must have
# finished successfully AND every expected queue (main + "<queue>-dlq") must exist. Services
# that depend_on localstack with condition: service_healthy therefore never start
# against a half-initialised broker.
# -----------------------------------------------------------------------------
set -euo pipefail

readonly ENDPOINT="http://localhost:4566"
readonly REGION="${AWS_DEFAULT_REGION:-us-east-1}"
readonly QUEUES=(
  "${SQS_ORDER_RESERVATION_QUEUE:-ticketing-order-reservation}"
  "${SQS_ORDER_PAYMENT_QUEUE:-ticketing-order-payment}"
)

init_state="$(curl -fsS --max-time 3 "${ENDPOINT}/_localstack/init/ready")"
if ! grep -Eq '"completed": *true' <<<"${init_state}"; then
  echo "READY init hooks still running: ${init_state}" >&2
  exit 1
fi
if grep -Eq '"state": *"ERROR"' <<<"${init_state}"; then
  echo "a READY init hook failed: ${init_state}" >&2
  exit 1
fi

queue_urls="$(awslocal --region "${REGION}" sqs list-queues --query 'QueueUrls[]' --output text | tr '\t' '\n')"
for queue in "${QUEUES[@]}"; do
  for name in "${queue}" "${queue}-dlq"; do
    if ! grep -Eq "/${name}\$" <<<"${queue_urls}"; then
      echo "queue ${name} does not exist" >&2
      exit 1
    fi
  done
done
