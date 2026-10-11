#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Waits until the given compose services report "healthy" (or fails on timeout).
# Handy in CI and in scripts; interactively "docker compose up -d --wait" does the same.
#
# Usage:
#   scripts/infra/wait-for-healthy.sh [--timeout SECONDS] service [service...]
#   scripts/infra/wait-for-healthy.sh --timeout 180 dynamodb-local localstack payment-mock auth-mock
# -----------------------------------------------------------------------------
set -euo pipefail
export MSYS_NO_PATHCONV=1 # Git Bash on Windows: do not rewrite /paths in docker arguments

cd "$(dirname "$0")/../.."

timeout_seconds=120
services=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --timeout)
      timeout_seconds="$2"
      shift 2
      ;;
    *)
      services+=("$1")
      shift
      ;;
  esac
done
if [[ ${#services[@]} -eq 0 ]]; then
  echo "usage: $0 [--timeout SECONDS] service [service...]" >&2
  exit 2
fi

status_of() {
  local container_ids status worst="healthy"
  container_ids="$(docker compose ps -q "$1" 2>/dev/null || true)"
  if [[ -z "${container_ids}" ]]; then
    echo "missing"
    return
  fi
  for container_id in ${container_ids}; do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_id}")"
    [[ "${status}" != "healthy" ]] && worst="${status}"
  done
  echo "${worst}"
}

deadline=$((SECONDS + timeout_seconds))
while true; do
  pending=()
  for service in "${services[@]}"; do
    status="$(status_of "${service}")"
    case "${status}" in
      healthy) ;;
      unhealthy | exited | dead)
        echo "${service} is ${status}; last health log:" >&2
        docker compose ps "${service}" >&2 || true
        exit 1
        ;;
      *) pending+=("${service}=${status}") ;;
    esac
  done
  if [[ ${#pending[@]} -eq 0 ]]; then
    echo "healthy: ${services[*]}"
    exit 0
  fi
  if ((SECONDS >= deadline)); then
    echo "timed out after ${timeout_seconds}s waiting for: ${pending[*]}" >&2
    exit 1
  fi
  sleep 2
done
