#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Container healthcheck for the ms-ticket image (API and WORKER modes).
#
# The eclipse-temurin JRE image ships neither curl nor wget, and starting a second
# JVM on every probe would cost ~100 MB and a second of CPU. Instead this probe
# speaks just enough HTTP/1.1 over bash's built-in /dev/tcp.
#
# Healthy  <=> GET ${HEALTHCHECK_PATH} answers "HTTP/1.x 200".
#
# Environment (all optional):
#   HEALTHCHECK_HOST             default 127.0.0.1
#   HEALTHCHECK_PORT             default ${SERVER_PORT:-8080}
#   HEALTHCHECK_PATH             default /actuator/health/readiness
#   HEALTHCHECK_TIMEOUT_SECONDS  default 3
# -----------------------------------------------------------------------------
set -uo pipefail

host="${HEALTHCHECK_HOST:-127.0.0.1}"
port="${HEALTHCHECK_PORT:-${SERVER_PORT:-8080}}"
path="${HEALTHCHECK_PATH:-/actuator/health/readiness}"
timeout_seconds="${HEALTHCHECK_TIMEOUT_SECONDS:-3}"

# The group-level 2>/dev/null only mutes bash's own "connect: ..." noise; fd 3 stays open.
if ! { exec 3<>"/dev/tcp/${host}/${port}"; } 2>/dev/null; then
  echo "healthcheck: cannot connect to ${host}:${port}" >&2
  exit 1
fi

printf 'GET %s HTTP/1.1\r\nHost: %s:%s\r\nAccept: application/json\r\nUser-Agent: container-healthcheck\r\nConnection: close\r\n\r\n' \
  "${path}" "${host}" "${port}" >&3

status_line=""
IFS= read -r -t "${timeout_seconds}" status_line <&3 || true
# Drain the (tiny) rest of the response so the server sees an orderly close.
while IFS= read -r -t 1 _ <&3; do :; done
exec 3<&- 3>&-

case "${status_line}" in
  "HTTP/1."?" 200"*)
    exit 0
    ;;
  *)
    echo "healthcheck: GET ${path} -> ${status_line:-no response}" >&2
    exit 1
    ;;
esac
