# End-to-end evidence: black-box verification of the running stack

This document records what was run against the local docker compose stack, what was observed, and what was
found. Every flow of the exercise was exercised through the public HTTP API only, with the infrastructure
(DynamoDB Local, SQS, the payment and auth mocks) inspected from outside for cross-checks.

| | |
|---|---|
| Date | 2026-10-11 (all times UTC) |
| Stack | compose project `ms-ticket` from the main checkout (commit `29c0be4`), image `ms-ticket:local`: `ticketing-api` (API mode) + `ticketing-worker` (worker mode), DynamoDB Local 3.3.1, LocalStack 4.9 (SQS), WireMock 3.13.2 (payment gateway), mock-oauth2-server 6.0.5 |
| Host | Windows 11, Docker Desktop (engine 27.4.0, compose 2.31, VM with 16 CPUs). Every container is capped at **1 CPU** by `docker-compose.yml`. |
| Tools | curl 8.11 + jq 1.8 ([`scripts/curl/flows.sh`](../../scripts/curl/flows.sh)), newman 6.2.3 ([`postman/`](../../postman)), k6 1.3.0 in `grafana/k6` ([`load-test/`](../../load-test)), the AWS CLI inside the LocalStack container (for DynamoDB and SQS cross-checks) |
| Configurations | **normal** (`docker-compose.yml`: hold 10 min, confirmation timeout 5 min, sweeper every 15 s) and **demo** ([`docker-compose.demo.yml`](../../docker-compose.demo.yml): hold 30 s, confirmation timeout 20 s, sweeper every 5 s) |

## 1. Results at a glance

| Flow | Result | Evidence |
|---|---|---|
| Event create (ADMIN, 201 + Location) / get / list with cursor pagination / validation 400 / unknown 404 | ✅ | §3.1 |
| Availability + SSE stream (frames on every change while an order flows) | ✅ | §3.2 |
| Place order → `202` + `Location: /api/v1/orders/{id}`, `AVAILABLE/QUEUED` | ✅ | §3.3 |
| Idempotent replay (same key + body → same `orderId`), same key + other body → `422`, missing / invalid key → `400` | ✅ | §3.4 |
| Order status: owner `200`, other customer `404` (anti-IDOR), ADMIN `200` | ✅ | §3.5 |
| Confirm → `PENDING_CONFIRMATION` → `SOLD` (+ second confirm is a no-op) | ✅ | §3.3 |
| Payment declined (`tok_declined`) → `PAYMENT_DECLINED`, tickets released | ✅ | §3.6 |
| Cancel reservation → `CANCELLED`, tickets released, later confirm → `409` | ✅ | §3.6 |
| Complimentary tickets (ADMIN) → `COMPLIMENTARY/COMPLETED`, separate counter | ✅ | §3.7 |
| Sold out: `409 SOLD_OUT` at admission, `REJECTED/SOLD_OUT` from the consumer when the race is lost | ✅ | §3.8 |
| Hold expiry (demo): `RESERVED → EXPIRED/HOLD_EXPIRED`, tickets back, confirm → `410 HOLD_EXPIRED` | ✅ | §4 |
| Payment gateway error (`tok_error`): 2 client retries, redelivery backoff 10/20/40/80 s, DLQ after 5 receives, reconciliation at the confirmation timeout | ✅ | §5 |
| Circuit breaker `paymentGateway`: CLOSED → OPEN → HALF_OPEN → CLOSED, calls fail fast while it is open | ✅ (logs only, see F-3) | §5 |
| Ticket audit history (ADMIN), `403` for customers | ✅ | §3.9 |
| `401` without / with a forged / an unmapped token, `403` wrong role, `429` + `Retry-After` | ✅ | §3.10 |
| Actuator health + Prometheus: `http.server.requests`, `sqs.messages`, `sqs.message.processing`, `sweeper.*` | ✅ (no circuit breaker metrics, see F-3) | §6 |
| Load: 500 buyers / 100 seats, zero oversell (counters, API recount, no ticket in two orders, DynamoDB cross-check) | ✅ in all 13 runs (run 1's API recount incomplete, §7.2 ¹) | §7 |
| Load: POST orders p95 < 300 ms | ✅ for the default profile on a warm API (10-94 ms); ❌ for 500 orders in 1 s (4-6 s) and on a cold API | §7 |
| Worker scaling `--scale ticketing-worker=3` under load | ✅ work spread across replicas, still zero oversell | §7.4 |
| Postman collection with newman | ✅ normal config: 109 requests, 195 assertions, 0 failures; demo config: 198 requests, 201 assertions, 0 failures | §8 |

Findings (not fixed, reported): **F-1** concurrent identical inventory transitions within one millisecond are
reported as applied to every caller, and the duplicate payment messages this produces reach the gateway and make
`finalizeSale` fail with an unmapped `IdempotentParameterMismatchException`. **F-2** framework-level errors
(404 route, 405, 413, 415) have no `errorCode`. **F-3** no circuit breaker metrics. Details in §9.

## 2. How to reproduce

```bash
docker compose up -d --wait                                   # normal configuration
scripts/curl/flows.sh                                         # 15 flows, PASS/FAIL summary
npx --yes newman run postman/ms-ticket.postman_collection.json -e postman/local.postman_environment.json

docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --wait ticketing-api ticketing-worker
scripts/curl/flows.sh expiry payment-error                     # time-based flows in seconds
docker compose -f docker-compose.yml up -d --wait ticketing-api ticketing-worker   # back to normal

# load test: see load-test/README.md (needs the load-customer-* mapping of docker/auth-mock/config.json)
```

## 3. Functional flows (normal configuration)

`scripts/curl/flows.sh` (all flows) finished with **79 passed, 0 failed, 2 skipped**. The skips are by design:
expiry and reconciliation wait 10 / 5 minutes with the normal configuration; §4 covers them with the demo
override. Excerpts follow (`>` request, `<` status and body).

### 3.1 Events

```text
> POST /api/v1/events  {"name":"Flows walkthrough","venue":"Arena Norte","eventDate":"2027-01-09T20:00:00Z","totalCapacity":20,"ticketPrice":75.50,"currency":"USD"}
< 201 {"eventId":"80e8d6b8-...","status":"ON_SALE","availableTickets":20,...}          Location: /api/v1/events/80e8d6b8-...
> GET /api/v1/events?status=ON_SALE&limit=1
< 200 {"items":[{"eventId":"ea0fb2d6-...",...}],"nextCursor":"ZXZlbnREYXRlPU46MTc5Njg3NjM0MzAwMCZldmVudElk..."}
> GET /api/v1/events?status=ON_SALE&limit=1&cursor=ZXZlbnREYXRlPU46...
< 200 {"items":[{"eventId":"51871790-...",...}],"nextCursor":"..."}                      (page 2 continues after page 1)
> GET /api/v1/events?limit=1&cursor=not-a-cursor
< 400 {"detail":"Malformed pagination cursor","title":"VALIDATION_ERROR","errorCode":"VALIDATION_ERROR",...}
> POST /api/v1/events  {"name":"","venue":"X","eventDate":"2020-01-01T00:00:00Z","totalCapacity":0,"ticketPrice":-1,"currency":"usd"}
< 400 {"errorCode":"VALIDATION_ERROR","errors":[{"field":"currency",...},{"field":"eventDate","message":"must be a future date"},...]}
> GET /api/v1/events/00000000-0000-0000-0000-000000000000
< 404 {"detail":"Event not found: 00000000-...","errorCode":"EVENT_NOT_FOUND",...}
```

### 3.2 Availability and the SSE stream

`curl -N` on the stream while an order is placed (→ `RESERVED`) and then cancelled: one frame per change
(`distinctUntilChanged`), each with `event:availability`.

```text
> GET /api/v1/events/7f48cdb5-.../availability/stream (curl -N, 8 s)
  id:1791694284377
  event:availability
  data:{"totalCapacity":20,"available":16,"reserved":0,"pendingConfirmation":0,"sold":2,"complimentary":2,...}
  id:1791694286376
  event:availability
  data:{"totalCapacity":20,"available":15,"reserved":1,"pendingConfirmation":0,"sold":2,"complimentary":2,...}
  id:1791694288381
  event:availability
  data:{"totalCapacity":20,"available":16,"reserved":0,"pendingConfirmation":0,"sold":2,"complimentary":2,...}
```

Every availability read in the walkthrough also checks `available + reserved + pending + sold + complimentary
== totalCapacity`.

### 3.3 Purchase happy path

```text
> POST /api/v1/events/80e8d6b8-.../orders  {"quantity":2}          Idempotency-Key: flows-1791692476-3cd94c30153e
< 202 {"orderId":"33218cb1-...","status":"AVAILABLE","processingStatus":"QUEUED","statusUrl":"/api/v1/orders/33218cb1-..."}
                                                                     Location: /api/v1/orders/33218cb1-...
  order reached RESERVED/ACTIVE                                      holdExpiresAt = now + 10 min
< 200 availability 18/2/0/0/0  (available/reserved/pending/sold/complimentary)
> POST /api/v1/orders/33218cb1-.../confirmation  {"paymentToken":"tok_approved"}
< 202 {"status":"PENDING_CONFIRMATION","processingStatus":"ACTIVE","ticketIds":["T-000011","T-000014"],...}
  order reached SOLD/COMPLETED                                       (< 1 s)
< 200 availability 18/0/0/2/0
> POST /api/v1/orders/33218cb1-.../confirmation  {"paymentToken":"tok_approved"}
< 202 {"status":"SOLD","processingStatus":"COMPLETED",...}          (idempotent by order state)
```

### 3.4 Idempotency-Key

```text
> POST .../orders {"quantity":2}  same key            < 202 {"orderId":"33218cb1-...","status":"SOLD",...}   same orderId, current state
> POST .../orders {"quantity":3}  same key            < 422 IDEMPOTENCY_KEY_REUSED
> POST .../orders {"quantity":1}  no key              < 400 INVALID_IDEMPOTENCY_KEY "The Idempotency-Key header is required"
> POST .../orders {"quantity":1}  key "bad_key!"      < 400 INVALID_IDEMPOTENCY_KEY "...must contain 8 to 64 letters, digits or dashes"
> POST .../orders {"quantity":11}                     < 400 VALIDATION_ERROR quantity "must be less than or equal to 10"
> customer-2 reuses customer-1's key                  < 202 different orderId (keys are scoped per customer)
```

Concurrency probe: **10 parallel POSTs** with the same customer, key and body all returned `202` with the same
`orderId`, and only 2 tickets were reserved.

### 3.5 Ownership (anti-IDOR)

```text
> GET    /api/v1/orders/33218cb1-...               customer-1 (owner)   < 200
> GET    /api/v1/orders/33218cb1-...               customer-2           < 404 ORDER_NOT_FOUND   (no existence leak)
> POST   /api/v1/orders/33218cb1-.../confirmation  customer-2           < 404 ORDER_NOT_FOUND
> DELETE /api/v1/orders/33218cb1-.../reservation   customer-2           < 404 ORDER_NOT_FOUND
> GET    /api/v1/orders/33218cb1-...               admin                < 200
> POST   /api/v1/orders/{customer order}/confirmation  admin            < 404 ORDER_NOT_FOUND   (confirmation is owner-only)
```

### 3.6 Payment declined and cancellation

```text
> POST /api/v1/orders/ee7d4784-.../confirmation {"paymentToken":"tok_declined"}  < 202 PENDING_CONFIRMATION
  order reached AVAILABLE/PAYMENT_DECLINED (reason=PAYMENT_DECLINED)             availability 17/1/0/2/0 -> 18/0/0/2/0

> DELETE /api/v1/orders/16a0c09c-.../reservation   < 202 {"status":"AVAILABLE","processingStatus":"CANCELLED","reason":"CUSTOMER_CANCELLATION",...}
  availability 16/2/0/2/0 -> 18/0/0/2/0
> DELETE /api/v1/orders/16a0c09c-.../reservation   < 202 CANCELLED          (idempotent)
> POST   /api/v1/orders/16a0c09c-.../confirmation  < 409 INVALID_ORDER_STATE
```

### 3.7 Complimentary tickets

```text
> POST /api/v1/events/80e8d6b8-.../complimentary-tickets {"beneficiaryId":"vip-guest-1","quantity":2}  (admin)  < 202 QUEUED
  order reached COMPLIMENTARY/COMPLETED                                             availability 16/0/0/2/2 (sold unchanged)
> same request as customer-1                                                         < 403 FORBIDDEN
> quantity 41                                                                        < 400 VALIDATION_ERROR
```

### 3.8 Sold out

```text
Tiny venue (capacity 2): customer-1 orders 2 -> RESERVED; 2.5 s later (2 s event cache):
> POST .../orders {"quantity":1} (customer-2)      < 409 {"detail":"Not enough tickets for event ...: requested 1, available 0","errorCode":"SOLD_OUT"}

Single seat (capacity 1): customer-1 and customer-2 order back to back, both admitted (202):
  order 1620d2f1... reached RESERVED/ACTIVE
  order bcca23f3... reached AVAILABLE/REJECTED (reason=SOLD_OUT)
< 200 availability 0/1/0/0/0                      (exactly one winner, no oversell)
Edge: quantity 4 on a 3-seat event is admitted (202) and rejected by the consumer with SOLD_OUT.
```

### 3.9 Ticket audit trail

```text
> GET /api/v1/events/80e8d6b8-.../tickets/T-000011/transitions   (admin)
< 200 [{"fromStatus":"AVAILABLE","toStatus":"RESERVED","reason":"RESERVATION","actor":"order-worker",...},
       {"fromStatus":"RESERVED","toStatus":"PENDING_CONFIRMATION","reason":"CUSTOMER_CONFIRMATION","actor":"customer-1",...},
       {"fromStatus":"PENDING_CONFIRMATION","toStatus":"SOLD","reason":"PAYMENT_APPROVED",...}]
> same request as customer-1                                     < 403 FORBIDDEN
```

### 3.10 Security and rate limiting

```text
> POST .../orders without Authorization            < 401 UNAUTHORIZED "A valid bearer token is required to access this resource"
> GET /api/v1/orders/... with a forged token        < 401 UNAUTHORIZED
> token of an unmapped client (no sub/roles/aud)    < 401 UNAUTHORIZED   (fail closed)
> POST /api/v1/events as CUSTOMER                   < 403 FORBIDDEN
> 40 parallel POSTs from customer-2                 < 24 x 400 (passed the limiter, rejected for the missing key), 16 x 429
                                                      {"detail":"Rate limit exceeded, retry after 1 s","errorCode":"RATE_LIMIT_EXCEEDED"}  Retry-After: 1
```

Other checks: a valid `X-Correlation-Id` is echoed, and an invalid one (`bad id <script>`) is replaced by a
generated UUID. Responses carry `X-Content-Type-Options`, `X-Frame-Options: DENY`, a restrictive CSP and
`Referrer-Policy: no-referrer`. Bodies above 256 KB get `413`.

## 4. Hold expiry and reconciliation (demo override)

`docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --no-build --wait ticketing-api ticketing-worker`
(worker log: `Starting the maintenance timer (every PT5S)`), then `scripts/curl/flows.sh expiry payment-error`
→ **11 passed, 0 failed**:

```text
== Hold expiry ==
  order ebb0163b... reached RESERVED/ACTIVE        hold TTL 30 s + sweeper interval
  order ebb0163b... reached AVAILABLE/EXPIRED (reason=HOLD_EXPIRED)
> POST /api/v1/orders/ebb0163b-.../confirmation {"paymentToken":"tok_approved"}
< 410 {"detail":"Reservation hold expired for order ebb0163b-...","errorCode":"HOLD_EXPIRED",...}
< 200 availability 20/0/0/0/0
== Payment gateway error ==
> POST /api/v1/orders/0afc0201-.../confirmation {"paymentToken":"tok_error"}   < 202 PENDING_CONFIRMATION
  order stays PENDING_CONFIRMATION while the gateway fails
  order 0afc0201... reached AVAILABLE/EXPIRED (reason=CONFIRMATION_TIMEOUT)   (~20 s, reconciliation sweeper)
```

The Postman folders 12 and 13 did the same under the override: the polling loop (`pm.execution.setNextRequest`)
ran 58 polls (≈ 30 s) until `EXPIRED/HOLD_EXPIRED` and 36 polls (≈ 20 s) until `EXPIRED/CONFIRMATION_TIMEOUT`.

Expiry at scale (normal configuration): the 10-minute holds left by the k6 runs were released by the sweeper
in batches (`Sweep reservation-expiration-sweeper finished: released=33 ...`, `released=31 ...`). For the event
of the first run, DynamoDB afterwards held 30 orders `AVAILABLE/EXPIRED`, 40 `SOLD/COMPLETED`, tickets
`AVAILABLE` 60 / `SOLD` 40, and the counters matched.

## 5. Payment gateway error with the normal configuration (retries, backoff, DLQ, reconciliation)

Order `75c892a6-...` confirmed with `tok_error` at 04:21:56.99. The WireMock journal shows **3 charge calls per
delivery** (1 + 2 client retries, 150-250 ms apart), and the deliveries follow the exponential redelivery backoff:

```text
charges (Idempotency-Key = orderId): 04:21:57.022 .168 .397 | 04:22:07.655 .806 04:22:08.014 | 04:22:28.652 .776 04:22:29.029 | ...
worker:
04:21:57.414 WARN  Message 41ddcee7-... of SQS queue ticketing-order-payment failed on delivery 1; redelivery in PT10S: PaymentGatewayException: Payment gateway charge failed: HTTP 500
04:22:08.028 WARN  ... failed on delivery 2; redelivery in PT20S
04:22:29.044 WARN  ... failed on delivery 3; redelivery in PT40S
04:23:10.080 WARN  ... failed on delivery 4; redelivery in PT1M20S
04:24:31.087 ERROR Message 41ddcee7-... failed on its last delivery allowed by the redrive policy (5 of 5): SQS moves it to the dead-letter queue on its next receive, in PT2M40S
04:26:57.378 INFO  Sweep reservation-expiration-sweeper finished: released=1, finalized=0, republished=0, failed=0
```

Order state and queue depths sampled every ~10 s (visible/in-flight):

```text
04:22:45 PENDING_CONFIRMATION/ACTIVE                         ticketing-order-payment=0/1 ticketing-order-payment-dlq=0/0
...      (unchanged)
04:26:53 PENDING_CONFIRMATION/ACTIVE                         ticketing-order-payment=0/1 ticketing-order-payment-dlq=0/0
04:27:05 AVAILABLE/EXPIRED reason=CONFIRMATION_TIMEOUT       updatedAt 04:26:57.349   (confirm + 5 min)
04:27:16 AVAILABLE/EXPIRED                                   ticketing-order-payment=0/1 ticketing-order-payment-dlq=1/0
```

The reconciliation asked the gateway for the charge (`GET /v1/charges/{orderId}`, 404 in the mock), so it
released the tickets instead of selling them. The dead letter is harmless: redriving it would be a no-op,
because the order is no longer `PENDING_CONFIRMATION`. The stack was left with 4 such dead letters in
`ticketing-order-payment-dlq`, one per `tok_error` order of the normal-configuration runs.
`ticketing-order-reservation-dlq` stayed empty through every test, including the load runs.

**Circuit breaker.** The `tok_error` orders of the walkthrough and newman runs (5 failed deliveries each) pushed
the payment gateway breaker over 50 % failures in its 20-call window. It opened, failed fast, and closed again
after successful trial calls. SQS redelivery acted as the retry:

```text
04:54:45.030 WARN  Circuit breaker paymentGateway changed: State transition from CLOSED to OPEN
04:55:37.595 WARN  Circuit breaker paymentGateway changed: State transition from OPEN to HALF_OPEN      (first call after the 30 s wait)
04:55:40.791 WARN  Payment gateway charge failed for idempotency key b8403abb-...: circuit breaker is open   (beyond the 5 trial calls)
04:55:40.791 WARN  Message 3083a899-... of SQS queue ticketing-order-payment failed on delivery 1; redelivery in PT10S: PaymentGatewayException: ... circuit breaker is open
04:55:40.805 WARN  Circuit breaker paymentGateway changed: State transition from HALF_OPEN to CLOSED
04:55:40.808 INFO  Order b8403abb-... payment approved: tickets SOLD
```

## 6. Observability

API `/actuator/prometheus` (public), with the common tags `application="ms-ticket"` and `runtime_mode`:

```text
http_server_requests_seconds_count{method="POST",outcome="SUCCESS",status="202",uri="/api/v1/events/{eventId}/orders",runtime_mode="api"} 9
http_server_requests_seconds_count{method="POST",outcome="CLIENT_ERROR",status="409",uri="/api/v1/events/{eventId}/orders",...} 1
http_server_requests_seconds_count{method="POST",outcome="CLIENT_ERROR",status="422",uri="/api/v1/events/{eventId}/orders",...} 1
```

Worker metrics are not published on the host. They were read inside the compose network
(`docker exec ms-ticket-localstack-1 curl -s http://ticketing-worker:8080/actuator/prometheus`):

```text
sqs_messages_total{outcome="acked",queue="ticketing-order-reservation",runtime_mode="worker"} 831.0
sqs_messages_total{outcome="acked",queue="ticketing-order-payment",runtime_mode="worker"} 180.0
sqs_message_processing_seconds_max{outcome="acked",queue="ticketing-order-reservation"} 0.0916
sqs_messages_in_flight{queue="ticketing-order-payment"} 0.0
sweeper_results_total{outcome="completed",type="reservation-expiration"} 53.0
sweeper_orders_total{result="released",type="reservation-expiration"} 37.0
http_client_requests_seconds_count{client_name="payment-mock",method="POST",status="201",uri="/v1/charges"} 180
```

`/actuator/health` and `/actuator/health/readiness` answer `200 UP`. **No `resilience4j_*` series exist** (F-3).

## 7. Load test: k6 ticket rush

[`load-test/k6/ticket-rush.js`](../../load-test/k6/ticket-rush.js) puts 500 customers (500 VUs, distinct JWT
subjects `load-customer-<run>-<n>`) on 100 seats, with 1-2 tickets per order and half of the winners paying.
The teardown proves zero oversell three ways: the counters, a recount through idempotent replays, and the
absence of any ticket id in two orders. See [`load-test/README.md`](../../load-test/README.md).

### 7.1 Headline run (default profile: 500 orders within 15 s, warm API, 1 worker, compose network)

```text
  █ THRESHOLDS
    checks                                                   ✓ 'rate>0.99' rate=100.00%
    duplicate_tickets                                        ✓ 'count==0' count=0
    http_req_duration{name:POST /api/v1/events/{eventId}/orders}  ✓ 'p(95)<300' p(95)=76.12ms
    http_req_failed                                          ✓ 'rate<0.01' rate=0.00%
    orders_unresolved                                        ✓ 'count==0' count=0
    oversell_violations                                      ✓ 'count==0' count=0
    recount_mismatches                                       ✓ 'count==0' count=0

  █ TOTAL RESULTS
    checks_succeeded...: 100.00% 1087 out of 1087
    ✓ token issued                     ✓ event created                ✓ order accepted: 202 + Location
    ✓ confirmation accepted (202)      ✓ admission 409 is SOLD_OUT    ✓ paid order is SOLD
    ✓ loser is REJECTED with SOLD_OUT  ✓ counters add up to the capacity
    ✓ no oversell: sold + held + complimentary <= capacity
    ✓ recount of SOLD/held orders matches the counters
    ✓ every assigned ticket belongs to exactly one order

    CUSTOM
    final_available...: 0     final_held...: 48     final_sold...: 52
    orders_accepted...: 74    orders_rejected_at_admission (409)...: 426    orders_rejected_sold_out (consumer)...: 7
    orders_reserved...: 67    orders_sold...: 37    orders_unresolved...: 0    tickets_won...: 100
    time_to_decision..: avg=1.02s med=1.01s p(95)=1.07s max=1.09s     (resolution = 1 s poll interval)
    time_to_sold......: avg=1.08s med=1.09s p(95)=1.17s max=1.2s

    HTTP
    http_req_duration{name:POST /api/v1/events/{eventId}/orders}: avg=15.7ms min=3.02ms med=5.27ms p(90)=48.24ms p(95)=76.12ms p(99)=116.39ms max=193.07ms
    http_req_failed...: 0.00% 0 out of 2218       http_reqs...: 2218

  console: availability {"totalCapacity":100,"available":0,"reserved":48,"pendingConfirmation":0,"sold":52,"complimentary":0}
           recount {"sold":52,"held":48,"soldOrders":37,"heldOrders":30,"rejected":7,"noOrder":426,"other":0}
           winning orders: 67, tickets assigned: 100, distinct: 100
```

(The overall `http_req_duration` p95 of 728 ms in that run comes from the 500 simultaneous token requests to
the 1-CPU auth-mock at VU start. That is why the threshold targets the order endpoint only.)

### 7.2 All runs

500 buyers, capacity 100, 1-2 tickets each. "Stress" means all 500 orders within 1 s. Runs 1-7 used the first
revision of the script, which differed only in which winners pay (`vu % 2` instead of `vu % 4`).

| # | Time | Profile | Workers | Client path | POST p95 | POST median | Failed requests | Accepted / 409 at admission / consumer `SOLD_OUT` | Sold / held tickets | Zero oversell |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 04:37 | stress | 1 | `host.docker.internal` | 10.46 s | 7.73 s | 2.02 % ¹ | 76 / 415 / 6 | 40 / 60 | ✅ counters, no duplicates (recount incomplete ¹) |
| 2 | 04:40 | stress | 1 | compose network | 5.74 s | 4.24 s | 0 | 150 / 350 / 88 | 24 / 76 | ✅ |
| 3 | 04:43 | 5 s window | 1 | compose network | 745 ms | 233 ms | 0 | 162 / 338 / 96 | 32 / 68 | ✅ |
| 4 | 04:45 | stress | **3** | compose network | 5.83 s | 2.65 s | 0 | 352 / 148 / 283 | 38 / 62 | ✅ (DynamoDB cross-check ✅) |
| 5 | 04:46 | default (15 s) | **3** | compose network | **94 ms** | 3.9 ms | 0 | 125 / 375 / 58 | 34 / 66 | ✅ all thresholds |
| 6 | 04:47 | default | **3** | `host.docker.internal` | **21 ms** | 4.8 ms | 0 | 72 / 428 / 3 | 38 / 62 | ✅ all thresholds |
| 7 | 04:49 | default | 1 | compose network | **10 ms** | 3.2 ms | 0 | 135 / 365 / 68 | 34 / 66 | ✅ all thresholds (DynamoDB cross-check ✅) |
| 8 | 04:58 | default, **cold API** ² | 1 | compose network | 862 ms | 8.3 ms | 0 | 123 / 377 / 53 | 51 / 49 | ✅ |
| 9 | 04:58 | stress | 1 | compose network | 4.20 s | 3.18 s | 0 | 145 / 355 / 75 | 46 / 54 | ✅ |
| 10 | 04:59 | default | **3** | compose network | 673 ms | 6.1 ms | 0 | 138 / 362 / 68 | 51 / 49 | ✅ |
| 11 | 05:00 | stress | **3** | compose network | 5.05 s | 3.74 s | 0 | 263 / 237 / 198 | 54 / 46 | ✅ |
| 12 | 05:01 | default (warm) | 1 | compose network | **76 ms** | 5.3 ms | 0 | 74 / 426 / 7 | 52 / 48 | ✅ all thresholds (§7.1) |
| 13 | 05:02 | default (warm) | **3** | compose network | **77 ms** | 4.6 ms | 0 | 130 / 370 / 61 | 43 / 57 | ✅ all thresholds |

¹ Run 1: Docker Desktop's host port proxy refused some of the 500 simultaneous connections to the auth-mock
(`dial tcp 192.168.65.254:8090: connect: connection refused`). 9 VUs got no token, and 18 replays in the
teardown had no token (401), so the recount was incomplete. Counters and the duplicate check passed. This is a
harness and networking limit; the in-network runs had 0 failed requests.
² Run 8 was the first heavy run after the API container had been recreated at 04:51 (the configuration
restore). The JIT was still compiling on the container's single CPU. The same settings on the warm API (run 12)
gave a p95 of 76 ms.

### 7.3 Zero oversell, cross-checked in DynamoDB

Read-only queries through the AWS CLI in the LocalStack container (tickets by event, orders filtered by event,
consistent read of the event item):

```text
run 7  (6582cf29-...)  event counters {available 0, reserved 66, pending 0, sold 34, complimentary 0, capacity 100}
                       tickets by status {SOLD: 34, RESERVED: 66} total 100
                       orders by state {AVAILABLE/REJECTED: 68, SOLD/COMPLETED: 34, RESERVED/ACTIVE: 33} total 135
                       tickets listed twice: []   held/sold tickets whose order does not list them: []
                       VERDICT: consistent, no oversell
run 4  (27548d91-..., 3 workers, 500 orders in 1 s)
                       event counters {available 0, reserved 62, pending 0, sold 38, ...}   tickets {RESERVED: 62, SOLD: 38}
                       orders {AVAILABLE/REJECTED: 283, SOLD/COMPLETED: 38, RESERVED/ACTIVE: 31} total 352
                       tickets listed twice: []   VERDICT: consistent, no oversell
```

### 7.4 Worker scaling under load

`docker compose ... up -d --no-build --no-deps --wait --scale ticketing-worker=3 ticketing-worker`. Work done per
replica during runs 10-11, counted from the worker logs:

```text
ticketing-worker-1: reserved=77 rejected_sold_out=125 payments_approved=23 sweeps_completed=10
ticketing-worker-2: reserved=28 rejected_sold_out=76  payments_approved=22 sweeps_completed=10
ticketing-worker-3: reserved=30 rejected_sold_out=65  payments_approved=24 sweeps_completed=10
```

The totals match k6 exactly: 135 reservations = 70 + 65 winning orders, 266 `SOLD_OUT` rejections = 68 + 198,
69 payments = 34 + 35 paid orders. Runs 4-6 gave the same picture. The sweepers run on every replica, but the
`ticketing-distributed-locks` lease lets only one sweep at a time.

### 7.5 Where the latency goes (analysis)

- CPU sampled during the 1-second drop with 3 workers: `dynamodb-local` 94-103 %, `ticketing-api` 99-109 %,
  each `ticketing-worker` ~100 %, `localstack` 45-71 %. That is every container at its 1-CPU compose limit.
- Bursts of 500 simultaneous requests against single layers: cached `GET /events/{id}` p95 62 ms,
  `/actuator/health` 160 ms, `GET .../availability` (one consistent DynamoDB read) **688 ms**. At a sustained
  200 req/s every endpoint stays at a few milliseconds. The burst cost is in the DynamoDB round-trips
  (DynamoDB Local is SQLite-backed) and in the API's single CPU for SDK marshalling and signing. A new order
  needs a `GetItem`, a conditional `PutItem` and an SQS `SendMessage`, while the worker runs
  `TransactWriteItems` against the same DynamoDB Local.
- The API's own `http.server.requests` timer (cumulative since the container started, run 1 included) averaged
  ~95 ms for `409` and ~295 ms for `202`, while run 1's clients waited 7.2 s on average. Requests queue on the
  saturated CPU before WebFlux dispatches them, so a burst hits a capacity limit of this local topology, not
  slow handler code.
- Locally, more workers do not speed up the drop: they share the single DynamoDB Local CPU. Runs 4 and 11
  admitted more orders than runs 2 and 9, most likely because the slower system made the 2 s event cache see
  `available = 0` later. The decisions themselves stayed correct. In AWS the API scales out behind the ALB and DynamoDB on-demand is
  not the constraint. A virtual waiting room in front of the API is listed in docs/limitations-and-improvements.md for real drops.
- Conclusion: **the 300 ms p95 target holds for 500 buyers arriving within 15 s (~33 orders/s at the start)
  on a warm API, with 1 or 3 workers**. It does not hold for 500 orders within 1 s, nor for the first heavy run
  after an API restart.

## 8. Postman collection (newman)

`npx --yes newman run postman/ms-ticket.postman_collection.json -e postman/local.postman_environment.json`

- 14 folders, one per flow (health, events, purchase, idempotency, ownership, declined, cancel, complimentary,
  audit, sold out, security, observability, hold expiry, gateway error).
- The collection pre-request script gets and caches client_credentials tokens for `customer-1`, `customer-2`
  and `admin` from `{{authUrl}}/token`. Ids are chained in collection variables (`eventId`, `orderId`,
  `ticketId`...). `Idempotency-Key` uses `{{$guid}}`, or a stored key when the replay is the point.
- Asynchronous states are polled with `pm.execution.setNextRequest(pm.info.requestId)` under a max-attempts
  guard. Collection-level tests check `X-Correlation-Id` on every API response and problem details
  (`application/problem+json` + `errorCode`) on every error.

| Configuration | Requests | Assertions | Failed | Duration |
|---|---|---|---|---|
| normal (final run, restored stack) | 109 | 195 | 0 | 16.1 s |
| demo override (expiry and reconciliation awaited) | 198 | 201 | 0 | 1 m 6.7 s |

With the normal configuration, folders 12 and 13 report `SKIPPED: hold TTL is 600 s (normal config)...` and
`SKIPPED: reconciliation happens after the 5 min confirmation timeout`. Folder 12 cancels its probe order.

## 9. Findings

### F-1 Concurrent identical inventory transitions within one millisecond all report "applied"; concurrent `finalizeSale` fails with an unmapped `IdempotentParameterMismatchException`

- **Reproduce:** reserve an order, then send 5 parallel `POST /api/v1/orders/{id}/confirmation`
  (`tok_approved`, same customer). Repeat on 6 orders. Do the same with 5 parallel
  `DELETE /api/v1/orders/{id}/reservation`.
- **Expected:** one confirmation (one cancellation) per order applies the transaction. The others lose the
  conditional check (`OrderStateChanged`), re-read and answer with the current state, so one payment message
  and one charge per order.
- **Actual:** every HTTP answer is correct (`202`, `PENDING_CONFIRMATION` / `CANCELLED`) and the counters stay
  conserved (34 available + 6 sold = 40). But the API logged `Order ... confirmed, payment requested`
  **20 times for 6 orders** and `Order ... cancelled, 1 tickets released` 20 times for 6 orders. The gateway
  received **18 charge calls for 6 orders** (4, 4, 2, 4, 1, 3). The worker logged 5 times:
  ```text
  WARN  SqsListenerContainer : Message 509f7821-... of SQS queue ticketing-order-payment failed on delivery 1; redelivery in PT10S:
        software.amazon.awssdk.services.dynamodb.model.IdempotentParameterMismatchException: Service returned error code
        IdempotentParameterMismatchException (Service: DynamoDb, Status Code: 400, ...)
  ```
  Each such message was redelivered 10 s later and then acknowledged as a duplicate (`is not pending
  confirmation (SOLD), duplicate delivery ignored`).
- **Cause (from reading the code):** `InventoryTransactionPersistenceImpl.clientRequestToken` is
  `sha256(operation | orderId | sortedTicketIds | now.toEpochMilli())`, and the audit transition ids derive from
  it. Two different callers issuing the same transition in the same millisecond therefore send byte-identical
  `TransactWriteItems` requests. DynamoDB treats the second one as an idempotent retry and returns success
  without applying it, so both callers take the `Applied` branch and run its side effects (another payment
  message, another charge). When the parameters differ, the losing call gets
  `IdempotentParameterMismatchException` instead of a conditional-check failure. Two concurrent `finalizeSale`
  calls differ in `paymentReference`. `TransactionCancellationTranslator` passes that exception through
  unchanged, so it becomes a failed SQS delivery in the worker, and it would become a 500 in an API path.
- **Impact:** no inventory corruption. Duplicate payment messages and charge calls rely on gateway-side
  idempotency (key = orderId). Each collision costs a failed delivery, a stack trace and 10 s of delay.
- **Possible fix (not applied):** add a per-command random nonce (generated once per command and reused
  across SDK retries) to the token, or map `IdempotentParameterMismatchException` to a re-read of the order.

### F-2 Framework-level errors have no `errorCode` (low)

`GET /api/v1/nope` (authenticated) → `404 {"detail":"No static resource api/v1/nope.","title":"Not Found",...}`,
`PUT /api/v1/events` → `405`, `Content-Type: text/plain` on POST orders → `415`, 300 KB body → `413`. They are
`application/problem+json` with `correlationId` and `timestamp`, but without the `errorCode` that every
application error carries, which the design promises for every error (fixed afterwards: framework errors now carry an errorCode). The 404 detail also reveals that the static
resource handler is active.

### F-3 No circuit breaker metrics (observability gap)

The worker's Prometheus output has no `resilience4j_*` series. `ResilienceConfig` builds
`CircuitBreakerRegistry.ofDefaults()` and never binds it to the `MeterRegistry` (for example with
`TaggedCircuitBreakerMetrics`). The breaker works (§5), but its state and failure rate can only be seen in the
logs, so no alarm can be raised on an open circuit.

### Not findings (expected behaviour, noted for the reader)

- `GET /api/v1/events?limit=1000` is clamped to 100. `status=CLOSED` returns an empty page.
- Replaying a `QUEUED` order republishes its reservation message, as designed. The consumer is idempotent and
  discards the duplicates (`already processed (ACTIVE), duplicate delivery ignored`).
- With the local profile, `/actuator/health` shows component details (`show-details: always`).

## 10. State left behind

The stack is running from the main checkout with **`docker-compose.yml` only** (normal timings, 1 worker,
auth-mock with the main checkout's `config.json`). It keeps the data created by these tests (DynamoDB Local is
in memory and is wiped when its container restarts) and 4 messages in `ticketing-order-payment-dlq` (§5). For
the load test I recreated the auth-mock temporarily with this branch's `config.json` (the `load-customer-*`
mapping), through an override that only replaced the mounted file. No container, image, volume or network
outside the compose project was touched; the throwaway k6 and probe containers ran with `--rm`.
