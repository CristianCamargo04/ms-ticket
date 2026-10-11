# Local Docker environment

Everything needed to run **ms-ticket** locally with production-like behaviour: DynamoDB, SQS with
dead-letter queues, an OAuth2/JWT issuer and a payment gateway, all emulated in containers.

| Service | Image | Host port (127.0.0.1) | Purpose |
|---|---|---|---|
| `dynamodb-local` | `amazon/dynamodb-local:3.3.1` | 8000 | DynamoDB (`-sharedDb -inMemory`) |
| `localstack` | `localstack/localstack:4.9` | 4566 | SQS: 2 queues + 2 DLQs created by `src/main/resources/scripts/localstack/init-sqs.sh` |
| `payment-mock` | `wiremock/wiremock:3.13.2` | 8089 | Payment gateway (`docker/wiremock`) |
| `auth-mock` | `ghcr.io/navikt/mock-oauth2-server:6.0.5` | 8090 | JWT issuer + JWKS (`docker/auth-mock/config.json`) |
| `ticketing-api` | `ms-ticket:local` (built from `Dockerfile`) | 8180 | REST API + actuator, `TICKETING_RUNTIMEMODE=API` |
| `ticketing-worker` | `ms-ticket:local` | - | SQS consumers + sweepers, `TICKETING_RUNTIMEMODE=WORKER` |
| `dynamodb-admin` (profile `tools`) | `aaronshaf/dynamodb-admin:5.3.4` | 8001 | Table browser |

`localstack/localstack:latest` requires a paid auth token, so the image is pinned to `4.9`.

## Quick start

```bash
cp .env.example .env                       # dummy emulator settings (git-ignored)
docker compose up -d --build --wait        # full stack, returns when everything is healthy
scripts/infra/verify-infra.sh              # smoke test of queues, tables, payments and tokens

TOKEN="$(scripts/infra/get-token.sh customer-1)"
curl -H "Authorization: Bearer ${TOKEN}" http://localhost:8180/api/v1/events

docker compose down -v                     # stop and wipe all state
```

Other useful commands:

```bash
# Infrastructure only, to run the application from the IDE with the "local" profile
docker compose up -d --wait dynamodb-local localstack payment-mock auth-mock

# Table browser on http://localhost:8001
docker compose --profile tools up -d dynamodb-admin

# Wait for services from a script / CI job
scripts/infra/wait-for-healthy.sh --timeout 180 dynamodb-local localstack
```

Startup order is enforced with `depends_on: condition: service_healthy`:
`dynamodb-local`, `localstack`, `auth-mock` -> `ticketing-api` (applies the DynamoDB migrations)
-> `ticketing-worker` (also waits for `payment-mock`). LocalStack only reports healthy once its init
hook succeeded and all four queues exist.

## How it works locally

### Issuer vs JWKS URL

The application talks to the same `auth-mock` through **two different URLs**, because `localhost`
means the developer's machine on the host but the container itself inside the compose network:

| What | URL | Configured by | Why this URL |
|---|---|---|---|
| Issuer (`iss` claim) | `http://localhost:8090/default` | `TICKETING_SECURITY_ISSUER` | Tokens are requested from the host, and mock-oauth2-server writes the URL it was called with into `iss`. The API compares the claim with this string; it never calls it. |
| JWKS (signing keys) | `http://auth-mock:8080/default/jwks` | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI` | The API downloads the keys over the compose network, where only the service name resolves. |

That is why the API uses `jwk-set-uri` plus an explicit issuer validator instead of `issuer-uri`
discovery: discovery would fetch `http://localhost:8090/...` from inside the container and fail.

- When the application runs from the IDE (infra-only mode), both URLs are on the host:
  issuer `http://localhost:8090/default`, JWKS `http://localhost:8090/default/jwks`.
- A client running *inside* the network (for example a containerised k6) must keep the host-side
  issuer by sending `Host: localhost:8090` when calling `http://auth-mock:8080/default/token`.
- A `401` with a valid-looking token almost always means `iss` differs, because the token was not
  issued through `http://localhost:${AUTH_MOCK_HOST_PORT}/default`.

### Getting a token

```bash
scripts/infra/get-token.sh customer-1          # roles [CUSTOMER]
scripts/infra/get-token.sh customer-2          # roles [CUSTOMER], a second owner for ownership checks
scripts/infra/get-token.sh admin               # roles [ADMIN]
scripts/infra/get-token.sh admin --claims      # also prints the decoded payload on stderr

TOKEN="$(scripts/infra/get-token.sh customer-1)"
curl -H "Authorization: Bearer ${TOKEN}" http://localhost:8080/api/v1/orders/<orderId>
```

Tokens live 3600 s; fetch a new one when the API starts answering `401`. Claims per client are
listed in [Token claims](#token-claims-auth-mock).

### Scaling workers

```bash
docker compose up -d --wait --scale ticketing-worker=3    # three consumers
docker compose ps ticketing-worker
docker compose logs -f ticketing-worker                   # every replica, prefixed ticketing-worker-N
docker compose up -d --wait --scale ticketing-worker=1    # back to one
```

- The worker publishes no host ports, so replicas never collide. The API publishes host port 8180 (8080 stays free for the app run from the IDE) and stays
  at one replica.
- All replicas long-poll the same two queues. SQS hands each message to one consumer at a time
  (visibility timeout 60 s), redelivers it if the consumer fails or dies, and moves it to the DLQ
  after 5 receives, so handlers must stay idempotent.
- Sweepers run in every replica but take a lease in the `ticketing-distributed-locks` table first,
  so only one replica sweeps at a time.
- Scale-down sends SIGTERM: in-flight messages are drained (30 s) within the 45 s
  `stop_grace_period`. Each replica is capped at 1 CPU / 768 MB and reserves 512 MB.

### Inspecting queues and DLQs

Inside the `localstack` container every queue URL is
`http://localhost:4566/000000000000/<queue>`. On Git Bash for Windows run
`export MSYS_NO_PATHCONV=1` first, so that `/paths` in the arguments are not rewritten.

```bash
docker compose exec localstack awslocal sqs list-queues

# Depth of every queue: visible messages, then in-flight messages
for q in ticketing-order-reservation ticketing-order-reservation-dlq \
         ticketing-order-payment ticketing-order-payment-dlq; do
  printf '%-34s ' "${q}"
  docker compose exec -T localstack awslocal sqs get-queue-attributes \
    --queue-url "http://localhost:4566/000000000000/${q}" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
    --query 'Attributes.[ApproximateNumberOfMessages,ApproximateNumberOfMessagesNotVisible]' --output text
done

# Peek at dead letters (visibility timeout 0 leaves them in the DLQ)
docker compose exec localstack awslocal sqs receive-message \
  --queue-url http://localhost:4566/000000000000/ticketing-order-payment-dlq \
  --max-number-of-messages 10 --visibility-timeout 0 --attribute-names All --message-attribute-names All

# Move the dead letters back to their source queue once the cause is fixed
docker compose exec localstack awslocal sqs start-message-move-task \
  --source-arn arn:aws:sqs:us-east-1:000000000000:ticketing-order-payment-dlq \
  --destination-arn arn:aws:sqs:us-east-1:000000000000:ticketing-order-payment

# Throw away everything in a queue
docker compose exec localstack awslocal sqs purge-queue \
  --queue-url http://localhost:4566/000000000000/ticketing-order-payment-dlq
```

Do not `receive-message` on the main queues while workers run: you would take messages away from
them, and each receive counts towards `maxReceiveCount`.

### Inspecting tables

The API creates the tables on startup (DynamoDB migrations, `TICKETING_MIGRATIONS_ENABLED=true`),
so in infra-only mode the list stays empty until the application has run once. Default names:
`ticketing-events`, `ticketing-tickets`, `ticketing-orders`, `ticketing-ticket-transitions`,
`ticketing-distributed-locks`, `ticketing-schema-migrations`. Data lives in memory only: restarting
`dynamodb-local` wipes it.

```bash
# Browser UI on http://localhost:8001
docker compose --profile tools up -d dynamodb-admin

# CLI, with the AWS CLI bundled in the localstack container
docker compose exec localstack aws --endpoint-url http://dynamodb-local:8000 dynamodb list-tables
docker compose exec localstack aws --endpoint-url http://dynamodb-local:8000 dynamodb describe-table --table-name ticketing-orders
docker compose exec localstack aws --endpoint-url http://dynamodb-local:8000 dynamodb scan --table-name ticketing-orders --max-items 10

# Or from the host with your own AWS CLI (-sharedDb: any dummy credentials work)
AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
  aws --endpoint-url http://localhost:8000 --region us-east-1 dynamodb list-tables
```

## Token claims (auth-mock)

| `client_id` | `sub` | `roles` | `aud` |
|---|---|---|---|
| `customer-1` | `customer-1` | `["CUSTOMER"]` | `ms-ticket` |
| `customer-2` | `customer-2` | `["CUSTOMER"]` | `ms-ticket` |
| `admin` | `admin` | `["ADMIN"]` | `ms-ticket` |
| `load-customer-*` | the client id | `["CUSTOMER"]` | `ms-ticket` |

`load-customer-*` exists for the k6 ticket rush (one subject per virtual user, so the per-subject rate
limit does not throttle the test) — see [load-test/README.md](../load-test/README.md). Any other `client_id` receives a token without `sub`, `roles` or `aud`, which the API rejects (fail
closed). The raw call behind `get-token.sh`:

```bash
curl -s -X POST http://localhost:8090/default/token \
  -d grant_type=client_credentials -d client_id=customer-1 -d client_secret=any -d scope=ms-ticket
```

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

Queue names equal the application defaults (`aws.sqs.order-reservation-queue`,
`aws.sqs.order-payment-queue`); each DLQ is named `<queue>-dlq`.

| Queue | Settings |
|---|---|
| `ticketing-order-reservation`, `ticketing-order-payment` | visibility timeout 60 s, long polling 20 s, retention 4 days, SSE-SQS, `RedrivePolicy` to the DLQ with `maxReceiveCount=5` |
| `ticketing-order-reservation-dlq`, `ticketing-order-payment-dlq` | retention 14 days, SSE-SQS, `RedriveAllowPolicy` restricted to the matching source queue |

The init script is idempotent (existing queues are reconciled), so it can be re-run at any time:
`docker compose exec localstack /etc/localstack/init/ready.d/10-init-sqs.sh`.

## Application container contract

The image runs `java org.springframework.boot.loader.launch.JarLauncher` as UID/GID 10001 and
listens on 8080. Its `HEALTHCHECK` (`/usr/local/bin/healthcheck`, plain bash over `/dev/tcp`
because the JRE image has no curl or wget) requires `GET /actuator/health/readiness` to answer `200`
without authentication. Locally the actuator shares port 8080 (`management.server.port` is not set),
so the compose healthchecks probe 8080 with no override. Only AWS moves the actuator to a separate
management port; the probe follows it through `MANAGEMENT_SERVER_PORT` (or `HEALTHCHECK_PORT`).

`/tmp` is the only writable path: the image creates it with mode `1777` and declares it a `VOLUME`,
so it stays writable (and exec-capable for Netty's native transport) under a read-only root
filesystem such as ECS `readonlyRootFilesystem`. Compose mounts a 64 MB tmpfs there instead.

Compose sets these variables, which override the `local` profile of `application.yml` through Spring Boot relaxed
binding:

| Variable | Value | Property |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `local` | |
| `TICKETING_RUNTIMEMODE` (and `TICKETING_RUNTIME_MODE`) | `API` / `WORKER` | `ticketing.runtime-mode` |
| `TICKETING_MIGRATIONS_ENABLED` | `true` (API) / `false` (worker) | `ticketing.migrations.enabled` |
| `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | from `.env` (`LOCAL_AWS_*`) | `aws.region`, `DefaultCredentialsProvider` |
| `AWS_ENDPOINTOVERRIDE_DYNAMODBENDPOINT` / `AWS_ENDPOINT_URL_DYNAMODB` | `http://dynamodb-local:8000` | `aws.endpoint-override.dynamodb-endpoint` / SDK-native |
| `AWS_ENDPOINTOVERRIDE_SQSENDPOINT` / `AWS_ENDPOINT_URL_SQS` | `http://localstack:4566` | `aws.endpoint-override.sqs-endpoint` / SDK-native |
| `AWS_SQS_ORDERRESERVATIONQUEUE` | `ticketing-order-reservation` | `aws.sqs.order-reservation-queue` |
| `AWS_SQS_ORDERPAYMENTQUEUE` | `ticketing-order-payment` | `aws.sqs.order-payment-queue` |
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
| `QueueDoesNotExist` / queues named `order-*-queue` | your `.env` predates the `ticketing-order-*` names: copy `SQS_ORDER_*_QUEUE` from `.env.example`, then `docker compose down -v && docker compose up -d --wait` |
| Port already in use | change the `*_HOST_PORT` values in `.env` (the issuer follows `AUTH_MOCK_HOST_PORT`) |
| `401` with a valid-looking token | the token was not issued through `http://localhost:${AUTH_MOCK_HOST_PORT}/default`, so `iss` differs |
| Scripts fail with `$'\r': command not found` | the checkout converted line endings; `.gitattributes` enforces LF, so re-checkout |
