# Local Docker environment

Everything needed to run **ms-ticket** locally with production-like behaviour: DynamoDB, SQS with
dead-letter queues, an OAuth2/JWT issuer and a payment gateway, all emulated in containers.

| Service | Image | Host port (127.0.0.1) | Purpose |
|---|---|---|---|
| `dynamodb-local` | `amazon/dynamodb-local:3.3.1` | 8000 | DynamoDB (`-sharedDb -inMemory`) |
| `localstack` | `localstack/localstack:4.9` | 4566 | SQS: 2 queues + 2 DLQs created by `src/main/resources/scripts/localstack/init-sqs.sh` |
| `payment-mock` | `wiremock/wiremock:3.13.2` | 8089 | Payment gateway (`docker/wiremock`) |
| `auth-mock` | `ghcr.io/navikt/mock-oauth2-server:6.0.5` | 8090 | JWT issuer + JWKS (`docker/auth-mock/config.json`) |
| `ticketing-api` | `ms-ticket:local` (built from `Dockerfile`) | 8080 | REST API, `TICKETING_RUNTIMEMODE=API` |
| `ticketing-worker` | `ms-ticket:local` | - | SQS consumers + sweepers, `TICKETING_RUNTIMEMODE=WORKER` |
| `dynamodb-admin` (profile `tools`) | `aaronshaf/dynamodb-admin:5.3.4` | 8001 | Table browser |

`localstack/localstack:latest` requires a paid auth token, so the image is pinned to `4.9`.

## Quick start

```bash
cp .env.example .env                       # dummy emulator settings (git-ignored)
docker compose up -d --build --wait        # full stack, returns when everything is healthy
scripts/infra/verify-infra.sh              # smoke test of queues, tables, payments and tokens

TOKEN="$(scripts/infra/get-token.sh customer-1)"
curl -H "Authorization: Bearer ${TOKEN}" http://localhost:8080/api/v1/events

docker compose down -v                     # stop and wipe all state
```

Other useful commands:

```bash
# Infrastructure only, to run the application from the IDE with the "local" profile
docker compose up -d --wait dynamodb-local localstack payment-mock auth-mock

# More consumers (the worker publishes no ports, so it scales freely)
docker compose up -d --wait --scale ticketing-worker=2

# Table browser on http://localhost:8001
docker compose --profile tools up -d dynamodb-admin

# Wait for services from a script / CI job
scripts/infra/wait-for-healthy.sh --timeout 180 dynamodb-local localstack
```

Startup order is enforced with `depends_on: condition: service_healthy`:
`dynamodb-local`, `localstack`, `auth-mock` -> `ticketing-api` (applies the DynamoDB migrations)
-> `ticketing-worker` (also waits for `payment-mock`). LocalStack only reports healthy once its init
hook succeeded and all four queues exist.

## Tokens (auth-mock)

```bash
scripts/infra/get-token.sh customer-1            # prints the access token
scripts/infra/get-token.sh admin --claims        # also prints the decoded payload on stderr
```

| `client_id` | `sub` | `roles` | `aud` |
|---|---|---|---|
| `customer-1` | `customer-1` | `["CUSTOMER"]` | `ms-ticket` |
| `customer-2` | `customer-2` | `["CUSTOMER"]` | `ms-ticket` |
| `admin` | `admin` | `["ADMIN"]` | `ms-ticket` |

Tokens live 3600 s. Any other `client_id` receives a token without `sub`, `roles` or `aud`, which
the API rejects (fail closed). The equivalent raw call:

```bash
curl -s -X POST http://localhost:8090/default/token \
  -d grant_type=client_credentials -d client_id=customer-1 -d client_secret=any -d scope=ms-ticket
```

**The issuer problem.** mock-oauth2-server derives the `iss` claim from the URL it is called with.
Tokens are requested from the host, so `iss = http://localhost:8090/default`. Inside the compose
network `localhost` is the application container itself, so the application:

- validates `iss` against the host URL: `TICKETING_SECURITY_ISSUER=http://localhost:8090/default`;
- downloads the signing keys through the service name:
  `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI=http://auth-mock:8080/default/jwks`.

A client running *inside* the network (e.g. a containerised k6) must keep the host-side issuer by
sending `Host: localhost:8090` when calling `http://auth-mock:8080/default/token`.

## Payment gateway (payment-mock)

`POST /v1/charges` requires an `Idempotency-Key` header and answers with
`ChargeResponseDto(paymentId, status, declineReason)`. The outcome depends on `paymentToken`:

| `paymentToken` | Response |
|---|---|
| `tok_approved*` | `201` `{"paymentId":"pay_<random>","status":"APPROVED","declineReason":null}` |
| `tok_declined*` | `201` `{"status":"DECLINED","declineReason":"INSUFFICIENT_FUNDS"}` |
| `tok_slow*` | `201 APPROVED` after 5 s (exceeds the client's 3 s response timeout) |
| `tok_error*` | `500` (exercises retries and the circuit breaker) |
| anything else | `201` `{"status":"DECLINED","declineReason":"INVALID_PAYMENT_TOKEN"}` |
| header missing | `400 MISSING_IDEMPOTENCY_KEY` (malformed key or body: `400 INVALID_REQUEST`) |

Responses echo `Idempotency-Key` and `X-Order-Id` (the request's `orderId`) as headers.
`GET /v1/charges/{idempotencyKey}` returns `404` by default: the mock keeps no charge state. To
simulate a payment captured before a timeout (reconciliation sweeper path), register a stub at
runtime:

```bash
curl -X POST http://localhost:8089/__admin/mappings -d '{
  "request":  {"method": "GET", "urlPath": "/v1/charges/<orderId>"},
  "response": {"status": 200, "jsonBody": {"paymentId": "pay_manual", "status": "APPROVED", "declineReason": null}}}'
```

## SQS (localstack)

| Queue | Settings |
|---|---|
| `order-reservation-queue`, `order-payment-queue` | visibility timeout 60 s, long polling 20 s, retention 4 days, SSE-SQS, `RedrivePolicy` to the DLQ with `maxReceiveCount=5` |
| `order-reservation-dlq`, `order-payment-dlq` | retention 14 days, SSE-SQS, `RedriveAllowPolicy` restricted to the matching source queue |

```bash
docker compose exec localstack awslocal sqs list-queues
docker compose exec localstack awslocal sqs get-queue-attributes --attribute-names All \
  --queue-url http://sqs.us-east-1.localhost.localstack.cloud:4566/000000000000/order-payment-queue
```

The init script is idempotent (existing queues are reconciled), so it can be re-run at any time:
`docker compose exec localstack /etc/localstack/init/ready.d/10-init-sqs.sh`.

## Application container contract

The image runs `java org.springframework.boot.loader.launch.JarLauncher` as UID 10001, listens on
8080, and its `HEALTHCHECK` (`/usr/local/bin/healthcheck`, plain bash over `/dev/tcp` because the JRE
image has no curl or wget) requires `GET /actuator/health/readiness` to answer `200` without
authentication. Compose sets these variables, which override `application-local.yml` through
Spring Boot relaxed binding:

| Variable | Value | Property |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | |
| `TICKETING_RUNTIMEMODE` (and `TICKETING_RUNTIME_MODE`) | `API` / `WORKER` | `ticketing.runtime-mode` |
| `TICKETING_MIGRATIONS_ENABLED` | `true` (API) / `false` (worker) | `ticketing.migrations.enabled` |
| `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | from `.env` (`LOCAL_AWS_*`) | `aws.region`, `DefaultCredentialsProvider` |
| `AWS_DYNAMODBENDPOINT` / `AWS_ENDPOINT_URL_DYNAMODB` | `http://dynamodb-local:8000` | `aws.dynamodb-endpoint` / SDK-native |
| `AWS_SQSENDPOINT` / `AWS_ENDPOINT_URL_SQS` | `http://localstack:4566` | `aws.sqs-endpoint` / SDK-native |
| `AWS_SQS_ORDERRESERVATIONQUEUE`, `AWS_SQS_ORDERPAYMENTQUEUE` | queue names from `.env` | `aws.sqs.*` |
| `TICKETING_SECURITY_ISSUER` | `http://localhost:8090/default` | `ticketing.security.issuer` |
| `TICKETING_SECURITY_AUDIENCE` | `ms-ticket` | `ticketing.security.audience` |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI` | `http://auth-mock:8080/default/jwks` | `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` |
| `TICKETING_PAYMENT_BASEURL` | `http://payment-mock:8080` | `ticketing.payment.base-url` |

## Security and resource posture

- Non-root users everywhere except LocalStack (UID 10001 for the app, `nobody` for WireMock).
- Read-only root filesystems with a size-capped `tmpfs` `/tmp`. It is `noexec` except for the app
  (Netty native epoll) and DynamoDB Local (native SQLite), which load native libraries from there.
- `cap_drop: [ALL]` and `no-new-privileges` on every container that tolerates it. The Docker socket
  is never mounted.
- Published ports are bound to `127.0.0.1`, so nothing is reachable from the LAN.
- CPU, memory and PID limits; `json-file` logs capped at 3 x 10 MB per container.
- Only dummy credentials, read from the git-ignored `.env`. They use a `LOCAL_` prefix so that a
  developer's real exported `AWS_*` variables can never be forwarded into a container.
- Allowlist `.dockerignore`: the build context holds only the Gradle wrapper, build scripts,
  `src/main` and the healthcheck probe, never `.git`, `.env`, tests, docs or infra.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `LOCAL_AWS_ACCESS_KEY_ID is not set` | `cp .env.example .env` |
| `localstack` stays unhealthy | `docker compose logs localstack`, look for `[init-sqs]` lines; `curl localhost:4566/_localstack/init/ready` |
| Port already in use | change the `*_HOST_PORT` values in `.env` (the issuer follows `AUTH_MOCK_HOST_PORT`) |
| `401` with a valid-looking token | the token was not issued through `http://localhost:${AUTH_MOCK_HOST_PORT}/default`, so `iss` differs |
| Scripts fail with `$'\r': command not found` | the checkout converted line endings; `.gitattributes` enforces LF, so re-checkout |
