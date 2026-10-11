#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Fetches an access token from the local auth-mock (mock-oauth2-server) using the
# OAuth2 client_credentials grant and prints it on stdout.
#
# Usage:
#   scripts/infra/get-token.sh [client_id] [--claims]
#
#   client_id  customer-1 | customer-2  -> roles [CUSTOMER]
#              admin                    -> roles [ADMIN]          (default: customer-1)
#   --claims   also print the decoded JWT payload on stderr
#
# Example:
#   TOKEN="$(scripts/infra/get-token.sh customer-1)"
#   curl -H "Authorization: Bearer ${TOKEN}" http://localhost:8080/api/v1/orders/<orderId>
#
# The token is requested through the HOST port on purpose, so that
# iss = http://localhost:8090/default, which is what the API validates
# (see the issuer note in docker-compose.yml).
#
# Environment: AUTH_ISSUER_URL (default http://localhost:${AUTH_MOCK_HOST_PORT:-8090}/default)
# -----------------------------------------------------------------------------
set -euo pipefail

client_id="customer-1"
show_claims=false
for arg in "$@"; do
  case "${arg}" in
    --claims) show_claims=true ;;
    -h | --help)
      sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    -*)
      echo "unknown option: ${arg}" >&2
      exit 2
      ;;
    *) client_id="${arg}" ;;
  esac
done

issuer_url="${AUTH_ISSUER_URL:-http://localhost:${AUTH_MOCK_HOST_PORT:-8090}/default}"

# mock-oauth2-server accepts any client secret; the claims come from docker/auth-mock/config.json.
response="$(curl -fsS --max-time 10 -X POST "${issuer_url}/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode "client_id=${client_id}" \
  --data-urlencode 'client_secret=not-validated-by-the-mock' \
  --data-urlencode 'scope=ms-ticket')"

token="$(printf '%s' "${response}" | sed -n 's/.*"access_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
if [[ -z "${token}" ]]; then
  echo "no access_token in response: ${response}" >&2
  exit 1
fi

if [[ "${show_claims}" == true ]]; then
  payload="$(printf '%s' "${token}" | cut -d. -f2 | tr '_-' '/+')"
  case $((${#payload} % 4)) in
    2) payload="${payload}==" ;;
    3) payload="${payload}=" ;;
  esac
  printf '%s' "${payload}" | base64 -d >&2
  echo >&2
fi

printf '%s\n' "${token}"
