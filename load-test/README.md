# Load test: ticket rush (k6)

`k6/ticket-rush.js` simulates a high-demand drop: **500 customers compete for 100 seats**, each buying
1 or 2 tickets (demand ≈ 7.5 × capacity). Then it proves **zero oversell** three independent ways. Measured
results and their analysis are in [docs/testing/e2e-evidence.md](../docs/testing/e2e-evidence.md#7-load-test-k6-ticket-rush).

## What one run does

| Phase | What happens |
|---|---|
| `setup()` | ADMIN creates a fresh event (`CAPACITY` tickets) and fixes the drop instant (`START_DELAY_S` from now). |
| 500 VUs, 1 iteration each | Each VU is a different customer (`load-customer-<run>-<n>`, its own JWT subject, so the per-customer rate limit does not interfere). It fetches its token, waits for the drop, places **one** order at a random instant of the `DROP_SPREAD_MS` window, polls it until the consumer decides (`RESERVED` or `REJECTED/SOLD_OUT`) and, for half of the winners, confirms with `tok_approved` and polls until `SOLD`. The other winners keep their 10-minute hold. |
| `teardown()` | **1. Counters:** `available + reserved + pending + sold + complimentary == capacity` and `sold + held + complimentary <= capacity` (strongly consistent read).<br>**2. Recount through the API:** every customer replays its own `Idempotency-Key` (same body → the existing order with its current state; nothing new can be admitted once the event is sold out), and the tickets in `SOLD` and held orders must equal the counters.<br>**3. No double assignment:** ADMIN reads every winning order, and no ticket id may appear in two orders. |

Expected answers during a drop are `202` and `409 SOLD_OUT` (soft admission check). `429` and `5xx` count as errors.

## Thresholds

| Metric | Threshold | Why |
|---|---|---|
| `http_req_duration{name:POST /api/v1/events/{eventId}/orders}` | `p(95) < 300 ms` (`P95_MS`) | admission must stay fast: the API only validates, writes the order and enqueues it |
| `http_req_failed` | `rate < 1 %` | 409 is expected, 429 / 5xx are not |
| `checks` | `rate > 99 %` | every functional check (202 + Location, loser is `REJECTED/SOLD_OUT`, paid order is `SOLD`...) |
| `oversell_violations`, `recount_mismatches`, `duplicate_tickets`, `orders_unresolved` | `count == 0` | the zero-oversell proofs above, and no order stuck in `QUEUED` |

Custom metrics in the summary: `orders_accepted`, `orders_rejected_at_admission` (409), `orders_reserved`,
`orders_rejected_sold_out` (consumer), `orders_sold`, `tickets_won`, `time_to_decision` and `time_to_sold`
(client-observed; their resolution is the 1 s poll interval), and `final_sold`, `final_held` and `final_available`.

## Prerequisites

1. The stack is running: `docker compose up -d --wait` (from the repository root).
2. The auth-mock issues CUSTOMER tokens for `client_id=load-customer-*` (with `sub` = the client id). The mapping
   lives in `docker/auth-mock/config.json`. Recreate the auth-mock after changing that file:
   `docker compose up -d --wait auth-mock`.
3. Docker can pull `grafana/k6:1.3.0` (a native `k6` binary works as well).

## Run

Recommended: run k6 **on the compose network**, so Docker Desktop's host port proxy is not part of the
measurement. The token endpoint is called through the service name with `Host: localhost:8090`, so the tokens
keep `iss = http://localhost:8090/default`, the issuer the API validates (see `docker/README.md`).

```bash
docker run --rm -i --network ms-ticket-network -v "$PWD/load-test/k6:/scripts:ro" \
  -e API_URL=http://ticketing-api:8080 \
  -e AUTH_URL=http://auth-mock:8080/default -e AUTH_HOST=localhost:8090 \
  grafana/k6:1.3.0 run /scripts/ticket-rush.js
```

Through the host instead (same tokens, but traffic crosses Docker Desktop's port proxy):

```bash
docker run --rm -i -v "$PWD/load-test/k6:/scripts:ro" \
  -e API_URL=http://host.docker.internal:8080 \
  -e AUTH_URL=http://host.docker.internal:8090/default -e AUTH_HOST=localhost:8090 \
  grafana/k6:1.3.0 run /scripts/ticket-rush.js
```

On Git Bash for Windows, prefix the commands with `MSYS_NO_PATHCONV=1` and use a Windows path for the volume
(for example `-v "D:/path/to/ms-ticket/load-test/k6:/scripts:ro"`).

With more consumers:

```bash
docker compose up -d --wait --no-deps --scale ticketing-worker=3 ticketing-worker
# ... run k6 ...
docker compose up -d --wait --no-deps --scale ticketing-worker=1 ticketing-worker
```

### Parameters (`-e NAME=value`)

| Variable | Default | Meaning |
|---|---|---|
| `API_URL` | `http://localhost:8080` | API base URL |
| `AUTH_URL` | `http://localhost:8090/default` | issuer base URL (`/token` is appended) |
| `AUTH_HOST` | (none) | `Host` header of the token requests, `localhost:8090` when `AUTH_URL` is not the host URL |
| `BUYERS` | `500` | customers (VUs), one order each |
| `CAPACITY` | `100` | seats of the event |
| `MAX_QTY` | `2` | tickets per order: 1..`MAX_QTY` |
| `DROP_SPREAD_MS` | `15000` | window in which the 500 orders arrive. `1000` is the stress variant: 500 orders in one second |
| `START_DELAY_S` | `15` | time for every VU to get its token before the drop |
| `POLL_INTERVAL_S` / `MAX_POLLS` | `1` / `90` | order polling |
| `P95_MS` | `300` | admission latency threshold |

## Reading the results

- **Warm the API up first.** The first heavy run after an API (re)start pays for JIT compilation on a 1-CPU
  container. On a fresh container we measured p95 860 ms, then 76 ms on the next run with the same
  settings. Run once to warm up, and measure the second run.
- **The stress variant (`DROP_SPREAD_MS=1000`) does not meet 300 ms locally** (p95 4-6 s). Every container is
  capped at one CPU, and DynamoDB Local (an SQLite-backed emulator) and the API both saturate. Correctness never
  changes: zero oversell, no duplicate tickets, recount equal to the counters.
- **More workers do not make the local drop faster.** All replicas write to the same single-CPU DynamoDB Local.
  Scaling demonstrates that competing consumers stay correct (conditional transactions), not throughput. In AWS,
  the API scales out behind the ALB and DynamoDB on-demand is not the bottleneck.
- Running through `host.docker.internal` can fail token requests with `connection refused` when 500 connections
  open at once (Docker Desktop's port proxy). That is a harness limit. The in-network command avoids it.
- Holds that are not paid expire after 10 minutes: the sweeper releases them, and the event's counters show the
  tickets as `available` again.
