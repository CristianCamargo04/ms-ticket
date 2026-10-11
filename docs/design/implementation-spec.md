# Implementation Specification — ms-ticket

This is the **single source of truth** for every contributor (human or agent). `PLAN.md` explains the *why*; this file pins the *exact* contracts. If something here conflicts with `PLAN.md`, this file wins. Folder/naming rules come from `prompt-jerarquia-carpetas.md` (base package `com.ticketing`, no company naming).

All identifiers, comments and Javadoc in **English**. Java 25, Spring Boot 4.1.1, WebFlux, AWS SDK v2 async (Netty), Jackson 3.

---

## 0. Toolchain facts (verified)

- Build: `./gradlew` (Gradle 9.8.1). Java toolchain 25 is auto-detected. Compiler flags: `-parameters -Xlint:all,-processing,-serial -Werror` → **every javac warning fails the build**. Fix warnings; only use a narrow `@SuppressWarnings` with a comment when unavoidable.
- Test stack: JUnit 5, Mockito (loaded as `-javaagent`, do NOT use `mockito-inline` hacks), `reactor-test` (`StepVerifier`), AssertJ, ArchUnit 1.5.1, `mockwebserver3-junit5` (package `mockwebserver3`), Testcontainers 2.0.5 (integration tests only).
- Spring Boot 4 test packages:
  - `org.springframework.boot.webflux.test.autoconfigure.WebFluxTest`
  - `org.springframework.test.context.bean.override.mockito.MockitoBean` (replaces `@MockBean`)
  - `org.springframework.test.web.reactive.server.WebTestClient`
  - `org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt()`
- Jackson 3: `tools.jackson.databind.json.JsonMapper` / `tools.jackson.databind.ObjectMapper` (package `tools.jackson.*`); annotations remain `com.fasterxml.jackson.annotation.*`. Spring Boot auto-configures a `JsonMapper` bean.
- Source sets: `src/main/java`, `src/test/java` (unit, fast, no Docker), `src/integrationTest/java` (Testcontainers; run with `./gradlew integrationTest`).
- Coverage gate: JaCoCo LINE ≥ 0.90 and BRANCH ≥ 0.90 over unit tests (`./gradlew test jacocoTestCoverageVerification`). Only `TicketingApplication` is excluded.
- Local infra images: `amazon/dynamodb-local:latest` (port 8000), `localstack/localstack:4.9` (SQS, port 4566 — `latest` requires a paid auth token, so the version is **pinned to 4.9**), `ghcr.io/navikt/mock-oauth2-server`, `wiremock/wiremock`.

---

## 1. Dependency rules (enforced by ArchUnit in `com.ticketing.architecture`)

```
presentation → business.usecase (interfaces) → persistence | client | cache (interfaces) → domain
config = composition root (may depend on everything; nothing depends on config)
```

| Package | May depend on (besides `java..`) | Must NOT depend on |
|---|---|---|
| `domain..` | `domain..`, `enums..` | anything else (no Spring, no Reactor, no AWS, no Jackson) |
| `enums..` | nothing | everything |
| `util..` | `reactor..` | every other `com.ticketing` package |
| `business..` | `business..`, `domain..`, `enums..`, `util..`, persistence **root** interfaces, `client.sdk` root interfaces, `client.rest.api..`, `cache` root interfaces, `reactor..`, `org.springframework.stereotype..`, `org.slf4j..` | `presentation..`, `config..`, `..impl..` of other layers, `persistence.entity..`, `persistence.dto..`, `client.dto..` |
| `persistence..` | `persistence..`, `domain..`, `enums..`, `util..`, AWS SDK, `reactor..`, Spring stereotypes | `presentation..`, `business..`, `client..`, `cache..`, `config..` |
| `client..` | `client..`, `domain..`, `enums..`, `util..`, AWS SDK, Spring WebFlux client, Jackson, resilience4j, `reactor..` | `presentation..`, `business..`, `persistence..`, `cache..`, `config..` |
| `cache..` | `cache..`, `domain..`, `enums..`, Caffeine, `reactor..` | `presentation..`, `business..`, `persistence..`, `client..`, `config..` |
| `presentation..` | `presentation..`, business usecase **interfaces** (root package `com.ticketing.business.usecase`), `domain..`, `enums..`, `util..`, Spring Web/Security, Jackson, jakarta.validation, `reactor..` | `business.usecase.impl..`, `persistence..`, `client..`, `cache..`, `config..` |

Other rules:
1. Every interface lives in the parent package; its implementation lives in an `impl` sub-package with suffix `Impl` (e.g. `persistence.OrderPersistence` → `persistence.impl.OrderPersistenceImpl`). Exception: `client.rest.api.XxxApi` → `client.rest.impl.XxxClientImpl`.
2. Classes named `*Impl` reside in `..impl..` and implement at least one non-impl `com.ticketing` interface.
3. Suffixes: `*UseCase` (business.usecase interfaces), `*UseCaseImpl`, `*Persistence`, `*PersistenceImpl`, `*Entity` (persistence.entity), `*Controller` (presentation interfaces), `*ControllerImpl`, `*RequestDto` / `*ResponseDto` (presentation.dto), `*Dto` (client.dto, persistence.dto), `*Mapper`, `*Enum` (enums), `*Exception` (domain.exception), `*Config` / `*Properties` (config), `*CacheService`, `*Client` / `*Api` (client).
4. **No blocking** in `presentation`, `business`, `persistence`, `client`, `cache`, `domain`, `util`: no `Mono.block*`, `Flux.block*`, `Thread.sleep`, `CompletableFuture.get/join`. (`config` may block only at startup — migration runner.)
5. Settings that a layer needs are passed as **settings records owned by that layer** (or by `domain` for business policies) and instantiated as beans in `config`. Layers never read `config` classes. Example: `persistence.impl.support.DynamoDbTableNames`, `client.sdk.impl.SqsQueueNames`, `cache.impl.EventCacheSettings`, `domain.order.OrderPolicy`.
6. Test mirror: every production class has `<SimpleName>Test` in the **same package** under `src/test/java`. Exempt: interfaces, `TicketingApplication`, and pure data carriers in `presentation.dto..`, `client.dto..`, `persistence.dto..`, `persistence.entity..`, `domain.internal_request..`, `domain.external_request..`, `domain.exception..` (exceptions are covered by `TicketingExceptionTest` + `GlobalErrorHandlerTest`), `config.properties..`. Nested types are covered by their top-level class test.

Stereotypes: use-case impls `@Service`; persistence impls `@Repository`; client impls `@Component`; cache impl `@Component`; mappers `@Component`; controllers `@RestController` (REST) / `@Component` (SQS, scheduler). Constructor injection only (no field injection, no Lombok).

---

## 2. Enums (`com.ticketing.enums`)

```java
public enum TicketStatusEnum {
    AVAILABLE, RESERVED, PENDING_CONFIRMATION, SOLD, COMPLIMENTARY;
    boolean canTransitionTo(TicketStatusEnum target); // matrix below
    boolean isFinal();        // SOLD, COMPLIMENTARY
    boolean isHeld();         // RESERVED, PENDING_CONFIRMATION
    boolean isAccountable();  // SOLD only (COMPLIMENTARY is final but not accountable)
}
```
Allowed transitions (anything else is invalid, including X→X):
`AVAILABLE→RESERVED`, `AVAILABLE→COMPLIMENTARY`, `RESERVED→PENDING_CONFIRMATION`, `RESERVED→AVAILABLE`, `PENDING_CONFIRMATION→SOLD`, `PENDING_CONFIRMATION→AVAILABLE`. Implement with an exhaustive `switch` expression.

- `OrderProcessingStatusEnum { QUEUED, ACTIVE, COMPLETED, REJECTED, EXPIRED, CANCELLED, PAYMENT_DECLINED, FAILED; boolean isTerminal() }` — terminal = all except QUEUED and ACTIVE.
- `OrderTypeEnum { STANDARD, COMPLIMENTARY }`
- `EventStatusEnum { PUBLISHING, ON_SALE, CLOSED }`
- `TransitionReasonEnum { RESERVATION, COMPLIMENTARY_ISSUED, CUSTOMER_CONFIRMATION, PAYMENT_APPROVED, PAYMENT_DECLINED, HOLD_EXPIRED, CONFIRMATION_TIMEOUT, CUSTOMER_CANCELLATION }`
- `DeadlineTypeEnum { DISPATCH, HOLD_EXPIRATION, PAYMENT_CONFIRMATION }` — which future action an order is waiting for (see §6.3).
- `MessageTypeEnum { ORDER_RESERVATION_REQUESTED, PAYMENT_REQUESTED }`
- `RuntimeModeEnum { ALL, API, WORKER; boolean apiEnabled(); boolean workerEnabled() }`
- `ErrorCodeEnum` with `int httpStatus()` and `boolean retryable()`:

| Constant | HTTP | retryable |
|---|---|---|
| VALIDATION_ERROR | 400 | no |
| INVALID_IDEMPOTENCY_KEY | 400 | no |
| UNAUTHORIZED | 401 | no |
| FORBIDDEN | 403 | no |
| EVENT_NOT_FOUND | 404 | no |
| ORDER_NOT_FOUND | 404 | no |
| EVENT_NOT_ON_SALE | 409 | no |
| SOLD_OUT | 409 | no |
| INVALID_ORDER_STATE | 409 | no |
| INVALID_TICKET_TRANSITION | 409 | no |
| HOLD_EXPIRED | 410 | no |
| IDEMPOTENCY_KEY_REUSED | 422 | no |
| RATE_LIMIT_EXCEEDED | 429 | no |
| INTERNAL_ERROR | 500 | no |
| INVENTORY_CONFLICT | 503 | yes |
| MESSAGE_PUBLISHING_FAILED | 503 | yes |
| PAYMENT_GATEWAY_UNAVAILABLE | 503 | yes |

---

## 3. Domain (`com.ticketing.domain`) — pure Java records/classes

### 3.1 `domain.data`
All timestamps are `java.time.Instant`; money is `BigDecimal`. Records validate invariants in the compact constructor (null checks with `Objects.requireNonNull`, non-negative counters). Lists are defensively copied (`List.copyOf`).

```java
record EventData(String eventId, String name, String venue, Instant eventDate, int totalCapacity,
                 BigDecimal ticketPrice, String currency, EventStatusEnum status,
                 int availableCount, int reservedCount, int pendingCount, int soldCount, int complimentaryCount,
                 long version, Instant createdAt, Instant updatedAt) {
    AvailabilitySnapshot toAvailabilitySnapshot(Instant capturedAt);
    boolean isOnSale();
    EventData withStatus(EventStatusEnum status, Instant now);
    static EventData newPublishing(String eventId, String name, String venue, Instant eventDate, int capacity,
                                   BigDecimal price, String currency, Instant now); // available = capacity
}

record TicketData(String eventId, String ticketId, TicketStatusEnum status, String orderId /*nullable*/,
                  Instant holdExpiresAt /*nullable*/, long version, Instant updatedAt) {
    static TicketData available(String eventId, String ticketId, Instant now);
    static String ticketIdFor(int sequence);        // "T-%06d", sequence 1-based
}

record OrderData(String orderId, String eventId, String customerId, OrderTypeEnum type, int quantity,
                 List<String> ticketIds, TicketStatusEnum ticketStatus, OrderProcessingStatusEnum processingStatus,
                 String reason /*nullable*/, String requestHash, Instant holdExpiresAt /*nullable*/,
                 Instant deadlineAt /*nullable*/, String paymentReference /*nullable*/,
                 long version, Instant createdAt, Instant updatedAt) {
    static OrderData newQueued(String orderId, String eventId, String customerId, OrderTypeEnum type, int quantity,
                               String requestHash, Instant dispatchDeadline, Instant now);
        // ticketStatus AVAILABLE, processingStatus QUEUED, ticketIds empty, deadlineAt = dispatchDeadline
    boolean isOwnedBy(String customerId);
    boolean isHoldExpired(Instant now);             // holdExpiresAt != null && !now.isBefore(holdExpiresAt)
    boolean matchesRequest(String requestHash);
    DeadlineTypeEnum deadlineType();                // QUEUED→DISPATCH; ACTIVE+RESERVED→HOLD_EXPIRATION;
                                                    // ACTIVE+PENDING_CONFIRMATION→PAYMENT_CONFIRMATION; else null
    BigDecimal totalAmount(BigDecimal unitPrice);
}

record TicketTransitionData(String eventId, String ticketId, String transitionId, TicketStatusEnum fromStatus,
                            TicketStatusEnum toStatus, String orderId, TransitionReasonEnum reason,
                            String actor, Instant occurredAt) { }

record AvailabilitySnapshot(String eventId, int totalCapacity, int available, int reserved,
                            int pendingConfirmation, int sold, int complimentary, Instant capturedAt) {
    boolean sameCountsAs(AvailabilitySnapshot other); // ignores capturedAt
}

record PageData<T>(List<T> items, String nextCursor /*nullable*/) { boolean hasMore(); }

record SweepResult(boolean lockAcquired, int released, int finalized, int republished, int failed) {
    static SweepResult skipped();  // lockAcquired=false, all zero
}
```

### 3.2 `domain.internal_request` (records)
```java
record CreateEventRequest(String name, String venue, Instant eventDate, int totalCapacity, BigDecimal ticketPrice, String currency)
record ListEventsRequest(EventStatusEnum status, int limit, String cursor /*nullable*/)
record PlaceOrderRequest(String eventId, String customerId, int quantity, String idempotencyKey, String requestHash, String correlationId)
record IssueComplimentaryRequest(String eventId, String beneficiaryId, int quantity, String issuedBy, String idempotencyKey, String requestHash, String correlationId)
record GetOrderRequest(String orderId, String requesterId, boolean privileged)
record ConfirmOrderRequest(String orderId, String customerId, String paymentToken, String correlationId)
record CancelOrderRequest(String orderId, String customerId)
record ReserveTicketsRequest(String orderId, String correlationId)
record ProcessOrderPaymentRequest(String orderId, String paymentToken, String correlationId)
record OrderReservationMessage(String orderId, String eventId, String correlationId, Instant occurredAt)
record PaymentRequestedMessage(String orderId, String paymentToken, String correlationId, Instant occurredAt)
```

### 3.3 `domain.external_request.payment`
```java
record PaymentChargeRequest(String idempotencyKey, String orderId, String customerId, BigDecimal amount, String currency, String paymentToken)
```

### 3.4 `domain.exception`
`public abstract class TicketingException extends RuntimeException` with `ErrorCodeEnum errorCode()` and `boolean retryable()` (delegates to the error code). Concrete (all `public final`, message in English, no sensitive data):

| Exception | ErrorCode | Constructor args |
|---|---|---|
| `DomainValidationException` | VALIDATION_ERROR or INVALID_IDEMPOTENCY_KEY | `(ErrorCodeEnum code, String message)` and `(String message)` → VALIDATION_ERROR |
| `EventNotFoundException` | EVENT_NOT_FOUND | `(String eventId)` |
| `EventNotOnSaleException` | EVENT_NOT_ON_SALE | `(String eventId, EventStatusEnum status)` |
| `OrderNotFoundException` | ORDER_NOT_FOUND | `(String orderId)` — also used when the requester does not own the order (no existence leak / anti-IDOR) |
| `InsufficientInventoryException` | SOLD_OUT | `(String eventId, int requested, int available)` |
| `InvalidOrderStateException` | INVALID_ORDER_STATE | `(String orderId, String detail)` |
| `InvalidTicketTransitionException` | INVALID_TICKET_TRANSITION | `(TicketStatusEnum from, TicketStatusEnum to)` |
| `ReservationExpiredException` | HOLD_EXPIRED | `(String orderId)` |
| `IdempotencyKeyConflictException` | IDEMPOTENCY_KEY_REUSED | `(String idempotencyKey)` |
| `RateLimitExceededException` | RATE_LIMIT_EXCEEDED | `(long retryAfterSeconds)` + getter |
| `InventoryConflictException` | INVENTORY_CONFLICT (retryable) | `(String message)` and `(String message, Throwable cause)` |
| `MessagePublishingException` | MESSAGE_PUBLISHING_FAILED (retryable) | `(String message, Throwable cause)` |
| `PaymentGatewayException` | PAYMENT_GATEWAY_UNAVAILABLE (retryable) | `(String message)` and `(String message, Throwable cause)` |

### 3.5 `domain.inventory`
```java
final class TicketStateMachine {            // utility, private ctor
    static void ensureTransition(TicketStatusEnum from, TicketStatusEnum to); // throws InvalidTicketTransitionException
    static TicketStatusEnum targetOf(TransitionReasonEnum reason);           // RESERVATION→RESERVED, ... HOLD_EXPIRED→AVAILABLE
}

record ReserveInventoryCommand(OrderData order, List<String> ticketIds, Instant holdExpiresAt, Instant now, String actor)
record IssueComplimentaryCommand(OrderData order, List<String> ticketIds, Instant now, String actor)
record ConfirmInventoryCommand(OrderData order, Instant confirmationDeadline, Instant now, String actor)
record FinalizeSaleCommand(OrderData order, String paymentReference, Instant now, String actor)
record ReleaseInventoryCommand(OrderData order, TicketStatusEnum expectedStatus, OrderProcessingStatusEnum resultingStatus,
                               TransitionReasonEnum reason, Instant now, String actor)
    // compact ctor: expectedStatus must be RESERVED or PENDING_CONFIRMATION; resultingStatus must be terminal

sealed interface InventoryWriteOutcome permits Applied, InsufficientInventory, StaleTicketSelection, OrderStateChanged {
    record Applied(OrderData order) implements InventoryWriteOutcome { }
    record InsufficientInventory() implements InventoryWriteOutcome { }   // event counter condition failed
    record StaleTicketSelection() implements InventoryWriteOutcome { }    // a ticket was no longer in the expected state
    record OrderStateChanged() implements InventoryWriteOutcome { }       // order condition failed (already processed / raced)
}

record RetryPolicy(int maxAttempts, Duration minBackoff, Duration maxBackoff, double jitter)
record SweeperPolicy(Duration lease, int batchSize, int concurrency, String instanceId)
```

### 3.6 `domain.order` and `domain.event`
```java
record OrderPolicy(Duration holdTtl, Duration confirmationTimeout, Duration republishAfter,
                   int maxTicketsPerOrder, int maxComplimentaryPerRequest, int candidateFactor) {
    Instant holdExpiresAt(Instant now); Instant confirmationDeadline(Instant now); Instant dispatchDeadline(Instant now);
}
sealed interface PaymentOutcome permits PaymentOutcome.Approved, PaymentOutcome.Declined, PaymentOutcome.NotFound {
    record Approved(String paymentReference) implements PaymentOutcome { }
    record Declined(String reason) implements PaymentOutcome { }
    record NotFound() implements PaymentOutcome { }
}
// domain.event
record EventPolicy(int maxCapacity, Duration availabilityStreamInterval, int ticketWriteConcurrency)
```
Defaults (from config): hold 10m, confirmation timeout 5m, republish after 30s, max tickets per order 10, max complimentary per request 40 (DynamoDB transactions allow 100 items: 2n+2 ≤ 100), candidate factor 3, max capacity 20000, stream interval 1s, ticket write concurrency 4, sweeper lease 30s, batch 100, concurrency 8; inventory retry 6 attempts 25ms..1s jitter 0.5; publish retry 3 attempts 100ms..1s.

---

## 4. Ports (interfaces)

### 4.1 Persistence (`com.ticketing.persistence`)
```java
interface EventPersistence {
    Mono<EventData> create(EventData event);                       // condition attribute_not_exists(eventId)
    Mono<EventData> findById(String eventId);                      // strongly consistent; empty if missing
    Mono<PageData<EventData>> findByStatus(EventStatusEnum status, int limit, String cursor); // GSI, opaque Base64 cursor; malformed cursor → DomainValidationException
    Mono<Boolean> updateStatus(String eventId, EventStatusEnum expected, EventStatusEnum target, Instant now); // false if condition failed
}
interface TicketPersistence {
    Mono<Long> saveAll(Flux<TicketData> tickets);                  // BatchWriteItem in chunks of 25, retries UnprocessedItems with backoff
    Flux<TicketData> findAvailable(String eventId, String fromTicketId, int limit); // GSI statusKey BETWEEN "AVAILABLE#"+from AND "AVAILABLE$"; eventually consistent
    Flux<TicketData> findByIds(String eventId, List<String> ticketIds);
}
interface OrderPersistence {
    Mono<Boolean> insertIfAbsent(OrderData order);                 // true=created, false=orderId already exists
    Mono<OrderData> findById(String orderId);                      // strongly consistent
    Flux<OrderData> findDueOrders(DeadlineTypeEnum type, Instant now, int limit); // queries every shard of deadline-index
    Mono<Boolean> transitionProcessingStatus(String orderId, OrderProcessingStatusEnum expected,
                                             OrderProcessingStatusEnum target, String reason, Instant now); // conditional; removes deadline when target is terminal
    Mono<Boolean> rescheduleDeadline(String orderId, OrderProcessingStatusEnum expected, Instant deadlineAt, Instant now);
}
interface InventoryTransactionPersistence {                       // one DynamoDB TransactWriteItems per call
    Mono<InventoryWriteOutcome> reserve(ReserveInventoryCommand command);
    Mono<InventoryWriteOutcome> issueComplimentary(IssueComplimentaryCommand command);
    Mono<InventoryWriteOutcome> confirm(ConfirmInventoryCommand command);
    Mono<InventoryWriteOutcome> finalizeSale(FinalizeSaleCommand command);
    Mono<InventoryWriteOutcome> release(ReleaseInventoryCommand command);
    // TransactionConflict / throttling / 5xx → error signal InventoryConflictException (retryable)
}
interface TicketTransitionPersistence {
    Flux<TicketTransitionData> findByTicket(String eventId, String ticketId); // ascending by time
}
interface DistributedLockPersistence {
    Mono<Boolean> tryAcquire(String lockName, String owner, Duration lease, Instant now); // conditional put: not exists OR leaseUntil < now OR owner = :owner
    Mono<Void> release(String lockName, String owner);                                     // conditional delete owner = :owner; swallow condition failure
}
```

### 4.2 Client
```java
// com.ticketing.client.sdk
interface SqsOrderQueueClient {
    Mono<String> publishReservationRequested(OrderReservationMessage message); // returns SQS messageId
    Mono<String> publishPaymentRequested(PaymentRequestedMessage message);
    // failures → MessagePublishingException
}
// com.ticketing.client.rest.api
interface PaymentGatewayChargeApi {
    Mono<PaymentOutcome> charge(PaymentChargeRequest request);       // Approved | Declined
    Mono<PaymentOutcome> findCharge(String idempotencyKey);          // Approved | Declined | NotFound
    // timeouts / 5xx / open circuit → PaymentGatewayException
}
```

### 4.3 Cache (`com.ticketing.cache`)
```java
interface EventCacheService {
    Mono<EventData> get(String eventId, Function<String, Mono<EventData>> loader); // Caffeine AsyncCache, short TTL; empty loader result is not cached
    void evict(String eventId);
}
```

### 4.4 Use cases (`com.ticketing.business.usecase`) — each has a single `execute` method
| Interface | Signature | Impl package |
|---|---|---|
| `CreateEventUseCase` | `Mono<EventData> execute(CreateEventRequest)` | `impl.event` |
| `GetEventUseCase` | `Mono<EventData> execute(String eventId)` (cache) | `impl.event` |
| `ListEventsUseCase` | `Mono<PageData<EventData>> execute(ListEventsRequest)` | `impl.event` |
| `GetEventAvailabilityUseCase` | `Mono<AvailabilitySnapshot> execute(String eventId)` (no cache, consistent read) | `impl.event` |
| `StreamEventAvailabilityUseCase` | `Flux<AvailabilitySnapshot> execute(String eventId)` | `impl.event` |
| `GetTicketHistoryUseCase` | `Flux<TicketTransitionData> execute(String eventId, String ticketId)` | `impl.event` |
| `PlaceOrderUseCase` | `Mono<OrderData> execute(PlaceOrderRequest)` | `impl.order` |
| `IssueComplimentaryTicketsUseCase` | `Mono<OrderData> execute(IssueComplimentaryRequest)` | `impl.order` |
| `GetOrderStatusUseCase` | `Mono<OrderData> execute(GetOrderRequest)` | `impl.order` |
| `ConfirmOrderUseCase` | `Mono<OrderData> execute(ConfirmOrderRequest)` | `impl.order` |
| `CancelOrderUseCase` | `Mono<OrderData> execute(CancelOrderRequest)` | `impl.order` |
| `ReserveTicketsUseCase` | `Mono<OrderData> execute(ReserveTicketsRequest)` | `impl.inventory` |
| `ProcessOrderPaymentUseCase` | `Mono<OrderData> execute(ProcessOrderPaymentRequest)` | `impl.inventory` |
| `ReleaseExpiredReservationsUseCase` | `Mono<SweepResult> execute()` | `impl.inventory` |
| `RepublishStaleOrdersUseCase` | `Mono<SweepResult> execute()` | `impl.inventory` |

Business impls receive `java.time.Clock` and (where randomness is needed) `java.util.random.RandomGenerator` as beans, so tests are deterministic. Use-case impls log with SLF4J (no PII beyond ids).

---

## 5. Use-case behaviour

**CreateEvent** — validate (name/venue non-blank ≤120; eventDate after now; 1 ≤ capacity ≤ `EventPolicy.maxCapacity`; price ≥ 0 with scale ≤ 2; currency `^[A-Z]{3}$`) → `DomainValidationException`. `eventId = UUID.randomUUID()`. `EventData.newPublishing(...)` → `eventPersistence.create` → `ticketPersistence.saveAll(Flux.range(1, capacity).map(i → TicketData.available(eventId, ticketIdFor(i), now)))` → `updateStatus(PUBLISHING→ON_SALE)` → return event with ON_SALE.

**GetEvent** — `eventCacheService.get(id, eventPersistence::findById)`; empty → `EventNotFoundException`.

**ListEvents** — limit clamp 1..100 (default 20 is applied by presentation); delegate.

**GetEventAvailability** — `eventPersistence.findById` (bypass cache) → empty → `EventNotFoundException` → `toAvailabilitySnapshot(clock.instant())`.

**StreamEventAvailability** — `Flux.interval(Duration.ZERO, policy.availabilityStreamInterval())` → `concatMap(getAvailability)` → `distinctUntilChanged` using `sameCountsAs`. First lookup error (not found) terminates the stream with the error.

**GetTicketHistory** — delegate to `TicketTransitionPersistence`.

**PlaceOrder** (API, fast path, never touches inventory):
1. `1 ≤ quantity ≤ maxTicketsPerOrder` else `DomainValidationException`.
2. `orderId = DeterministicIdUtil.nameBasedUuid(customerId + ":" + idempotencyKey)` (UUID v5).
3. `orderPersistence.findById(orderId)`; if present → `matchesRequest(hash)` ? (if still QUEUED re-publish the reservation message, ignoring publish errors) return it : `IdempotencyKeyConflictException`.
4. Event via cache: missing → `EventNotFoundException`; not ON_SALE → `EventNotOnSaleException`; `availableCount == 0` → `InsufficientInventoryException` (soft admission check — the real check happens in the consumer).
5. `OrderData.newQueued(..., dispatchDeadline = policy.dispatchDeadline(now))` → `insertIfAbsent`; `false` (concurrent duplicate) → reload and apply step 3.
6. Publish `OrderReservationMessage` with publish retry. On final failure log a warning and still return the order (the dispatch sweeper republishes it after `republishAfter`).

**IssueComplimentaryTickets** — same as PlaceOrder but: type COMPLIMENTARY, `customerId = beneficiaryId`, id from `issuedBy + ":" + idempotencyKey`, limit `maxComplimentaryPerRequest`, no soft availability check other than not-found / not-on-sale.

**ReserveTickets** (SQS consumer, idempotent):
1. `findById` → empty → `OrderNotFoundException` (non-retryable: message is acknowledged).
2. `processingStatus != QUEUED` → return order unchanged (duplicate delivery).
3. Event consistent read; missing or not ON_SALE → `transitionProcessingStatus(QUEUED→REJECTED, "EVENT_NOT_ON_SALE")` → return reloaded order.
4. `event.availableCount < quantity` → reject with reason `SOLD_OUT`.
5. Candidates: random start ticket `ticketIdFor(random.nextInt(totalCapacity) + 1)`; `findAvailable(eventId, start, quantity * candidateFactor)`; if fewer than `quantity`, also `findAvailable(eventId, "", quantity * candidateFactor)`; distinct by ticketId; random sample of `quantity`. If still fewer than `quantity` → `InventoryConflictException` (index lag → retry).
6. STANDARD → `inventory.reserve(new ReserveInventoryCommand(order, ids, policy.holdExpiresAt(now), now, "order-worker"))`; COMPLIMENTARY → `inventory.issueComplimentary(...)`.
7. Pattern-match the outcome: `Applied` → return order; `InsufficientInventory` → reject `SOLD_OUT`; `StaleTicketSelection` → `InventoryConflictException`; `OrderStateChanged` → return reloaded order.
8. Steps 3–7 are wrapped in `retryWhen(ReactiveRetryUtil.backoff(inventoryRetryPolicy, TicketingException::retryable))`. Exhaustion propagates the error (message is not deleted → SQS redelivers → DLQ after maxReceiveCount).

**ConfirmOrder** (API):
1. `findById`; missing or `!isOwnedBy(customerId)` → `OrderNotFoundException`.
2. `ticketStatus == PENDING_CONFIRMATION` → re-publish payment message (idempotent) and return; `SOLD` → return.
3. Not (`ACTIVE` and `RESERVED`) → `InvalidOrderStateException`.
4. `isHoldExpired(now)` → `ReservationExpiredException`.
5. `inventory.confirm(new ConfirmInventoryCommand(order, policy.confirmationDeadline(now), now, customerId))`.
6. `Applied` → publish `PaymentRequestedMessage` (with retry; on failure log, keep PENDING — the customer may retry, and the sweeper reconciles) → return. `OrderStateChanged` → reload: PENDING_CONFIRMATION/SOLD → return it; hold expired → `ReservationExpiredException`; else `InvalidOrderStateException`. Other outcomes → `InventoryConflictException`.

**CancelOrder** (API): owner check as above. `QUEUED` → `transitionProcessingStatus(QUEUED→CANCELLED, "CUSTOMER_CANCELLATION")`. `ACTIVE+RESERVED` → `inventory.release(order, RESERVED, CANCELLED, CUSTOMER_CANCELLATION)`. Already `CANCELLED` → return. Anything else → `InvalidOrderStateException`. Return reloaded order.

**GetOrderStatus**: missing or (`!privileged && !isOwnedBy(requesterId)`) → `OrderNotFoundException`.

**ProcessOrderPayment** (SQS consumer, idempotent):
1. Missing → `OrderNotFoundException`. `ticketStatus != PENDING_CONFIRMATION` → return unchanged.
2. Event (for price); amount = `totalAmount(event.ticketPrice())`.
3. `paymentApi.charge(new PaymentChargeRequest(orderId /*idempotency key*/, ...))` — errors propagate (retryable).
4. `Approved` → `finalizeSale`; `Declined` → `release(order, PENDING_CONFIRMATION, PAYMENT_DECLINED, PAYMENT_DECLINED)`. `OrderStateChanged` → reload and, if the payment was approved but the order is no longer pending, log an ERROR `payment captured for released order` (manual refund — documented limitation). Return reloaded order.

**ReleaseExpiredReservations** (sweeper):
1. `lockPersistence.tryAcquire("reservation-expiration-sweeper", instanceId, lease, now)`; false → `SweepResult.skipped()`.
2. `findDueOrders(HOLD_EXPIRATION, now, batch)` → `flatMap(concurrency)` → `release(order, RESERVED, EXPIRED, HOLD_EXPIRED)`; count `Applied` as released.
3. `findDueOrders(PAYMENT_CONFIRMATION, now, batch)` → `paymentApi.findCharge(orderId)`: `Approved` → `finalizeSale` (finalized++); `Declined`/`NotFound` → `release(order, PENDING_CONFIRMATION, EXPIRED, CONFIRMATION_TIMEOUT)` (released++).
4. Per-order errors are logged and counted as `failed` (`onErrorResume` per element) — one bad order never aborts the sweep.
5. Always release the lock (`Mono.usingWhen` or `doFinally`-equivalent reactive release).

**RepublishStaleOrders** (sweeper): lock `"order-dispatch-sweeper"`; `findDueOrders(DISPATCH, now, batch)` → publish `OrderReservationMessage` → `rescheduleDeadline(orderId, QUEUED, policy.dispatchDeadline(now), now)`; count `republished` / `failed`.

---

## 6. DynamoDB schema (`persistence`)

All timestamps are stored as **Number (epoch milliseconds)** so range conditions compare numerically. Money as String (`BigDecimal.toPlainString()`). Table names come from configuration (`aws.dynamodb.tables.*`). Default local names: `ticketing-events`, `ticketing-tickets`, `ticketing-orders`, `ticketing-ticket-transitions`, `ticketing-distributed-locks`, `ticketing-schema-migrations`.

### 6.1 Tables
| Table | Keys | Attributes | Indexes |
|---|---|---|---|
| events | PK `eventId` S | `name` S, `venue` S, `eventDate` N, `totalCapacity` N, `ticketPrice` S, `currency` S, `status` S, `availableCount` N, `reservedCount` N, `pendingCount` N, `soldCount` N, `complimentaryCount` N, `version` N, `createdAt` N, `updatedAt` N | GSI `status-eventDate-index` (PK `status` S, SK `eventDate` N, ALL) |
| tickets | PK `eventId` S, SK `ticketId` S | `status` S, `orderId` S?, `holdExpiresAt` N?, `statusKey` S (`<STATUS>#<ticketId>`), `version` N, `updatedAt` N | GSI `eventId-statusKey-index` (PK `eventId`, SK `statusKey`, INCLUDE `status`, `version`, `updatedAt` — enough to build `TicketData` for AVAILABLE tickets without a second read) |
| orders | PK `orderId` S | `eventId`, `customerId`, `orderType`, `quantity` N, `ticketIds` L<S>, `ticketStatus`, `processingStatus`, `reason`?, `requestHash`, `holdExpiresAt` N?, `paymentReference`?, `version` N, `createdAt` N, `updatedAt` N, **`deadlinePartition` S?** (`<DeadlineTypeEnum>#<shard>`), **`deadlineAt` N?** | GSI `deadline-index` (PK `deadlinePartition`, SK `deadlineAt`, ALL) — sparse |
| ticket-transitions | PK `ticketKey` S (`<eventId>#<ticketId>`), SK `transitionKey` S (`<epochMillis 13-digit zero-padded>#<transitionId>`) | `eventId`, `ticketId`, `orderId`, `fromStatus`, `toStatus`, `reason`, `actor`, `occurredAt` N | — |
| distributed-locks | PK `lockName` S | `owner` S, `leaseUntil` N, `expiresAt` N (epoch **seconds**, DynamoDB TTL) | — |
| schema-migrations | PK `migrationId` S | `appliedAt` N, `checksum` S | — (local/ci only) |

Billing mode PAY_PER_REQUEST everywhere.

### 6.2 Deadline index (sparse)
`deadlinePartition`/`deadlineAt` are written **only** while `order.deadlineType() != null`, and REMOVED when the order reaches a terminal status. Shard = `Math.floorMod(orderId.hashCode(), deadlineShards)` (default 8 shards, configurable). `findDueOrders(type, now, limit)` queries each shard `deadlinePartition = :p AND deadlineAt <= :now` and merges.

### 6.3 Transactions (`InventoryTransactionPersistenceImpl`, low-level `DynamoDbAsyncClient.transactWriteItems`)
`ClientRequestToken` = first 36 chars of `sha256Hex(operation + orderId + sortedTicketIds)` (idempotent SDK retries). Item order is **fixed** so cancellation reasons can be mapped by index: `[0] event, [1..n] tickets, [n+1] order, [n+2..2n+1] transition puts`. Every ticket and order update does `ADD version :one`, `SET updatedAt = :now`.

| Operation | Event update (index 0) | Ticket updates | Order update | Audit |
|---|---|---|---|---|
| reserve | `availableCount -= q, reservedCount += q` IF `#status = ON_SALE AND availableCount >= :q` | AVAILABLE→RESERVED, set `orderId`, `holdExpiresAt`, `statusKey` IF `#status = AVAILABLE` | IF `processingStatus = QUEUED`: ticketStatus RESERVED, processingStatus ACTIVE, ticketIds, holdExpiresAt, deadline (HOLD_EXPIRATION, holdExpiresAt) | Put per ticket, IF `attribute_not_exists(ticketKey)` |
| issueComplimentary | `availableCount -= q, complimentaryCount += q` IF `#status = ON_SALE AND availableCount >= :q` | AVAILABLE→COMPLIMENTARY, set `orderId` IF `#status = AVAILABLE` | IF `processingStatus = QUEUED`: COMPLIMENTARY / COMPLETED, ticketIds, REMOVE deadline | ✓ |
| confirm | `reservedCount -= q, pendingCount += q` IF `attribute_exists(eventId)` | RESERVED→PENDING_CONFIRMATION IF `#status = RESERVED AND orderId = :o` | IF `ticketStatus = RESERVED AND processingStatus = ACTIVE AND holdExpiresAt > :now AND customerId = :c`: PENDING_CONFIRMATION, deadline (PAYMENT_CONFIRMATION, confirmationDeadline) | ✓ |
| finalizeSale | `pendingCount -= q, soldCount += q` | PENDING_CONFIRMATION→SOLD IF `#status = PENDING_CONFIRMATION AND orderId = :o` | IF `ticketStatus = PENDING_CONFIRMATION AND processingStatus = ACTIVE`: SOLD / COMPLETED, paymentReference, REMOVE deadline | ✓ |
| release | `availableCount += q`, `reservedCount -= q` or `pendingCount -= q` | expected→AVAILABLE, REMOVE `orderId`, `holdExpiresAt`, statusKey `AVAILABLE#id` IF `#status = :expected AND orderId = :o` | IF `ticketStatus = :expected AND processingStatus = ACTIVE` (+ `holdExpiresAt <= :now` when reason HOLD_EXPIRED; + `deadlineAt <= :now` when CONFIRMATION_TIMEOUT): AVAILABLE / resultingStatus, reason = reason name, REMOVE deadline | ✓ |

Cancellation mapping (priority order): order item `ConditionalCheckFailed` → `OrderStateChanged`; event item `ConditionalCheckFailed` → `InsufficientInventory`; any ticket `ConditionalCheckFailed` → `StaleTicketSelection`; any `TransactionConflict` / `ThrottlingError` / `ProvisionedThroughputExceeded` / `InternalServerError` / SDK throttling exceptions → error `InventoryConflictException`. On `Applied`, the returned `OrderData` is the input order with the new state applied in memory (version + 1).

### 6.4 Migrations
`src/main/resources/db/changelog/db.changelog-master.yaml`:
```yaml
databaseChangeLog:
  - migration: { id: 20261010100000_TCK-001, file: db/migrations/20261010100000_TCK-001/create-events-table.json }
  ...
```
Each JSON file mirrors the DynamoDB `CreateTable` API (`TableName` is a logical key: `events`, `tickets`, ... resolved to the configured physical name; `AttributeDefinitions`, `KeySchema`, `GlobalSecondaryIndexes`, `BillingMode`, optional `TimeToLiveSpecification`). `config.migration.DynamoMigrationRunner` (enabled by `ticketing.migrations.enabled=true`, only in `local`/`ci`) applies pending migrations in order, records them in `schema-migrations` with a SHA-256 checksum, skips tables that already exist and fails fast on checksum mismatch. In AWS, Terraform owns the schema (same definitions).

---

## 7. Presentation

### 7.1 REST contracts — interfaces carry the Spring mapping annotations; `*ControllerImpl` is `@RestController implements` the interface.

| Interface | Method | Mapping | Auth |
|---|---|---|---|
| `EventRestController` | `Mono<ResponseEntity<EventResponseDto>> createEvent(CreateEventRequestDto)` | `POST /api/v1/events` → 201 + Location | ADMIN |
| | `Mono<PageResponseDto<EventResponseDto>> listEvents(EventStatusEnum status = ON_SALE, int limit = 20, String cursor)` | `GET /api/v1/events` | public |
| | `Mono<EventResponseDto> getEvent(String eventId)` | `GET /api/v1/events/{eventId}` | public |
| `AvailabilityRestController` | `Mono<AvailabilityResponseDto> getAvailability(String eventId)` | `GET /api/v1/events/{eventId}/availability` | public |
| | `Flux<ServerSentEvent<AvailabilityResponseDto>> streamAvailability(String eventId)` | `GET /api/v1/events/{eventId}/availability/stream` (`text/event-stream`, event name `availability`) | public |
| `TicketHistoryRestController` | `Flux<TicketTransitionResponseDto> getTicketHistory(String eventId, String ticketId)` | `GET /api/v1/events/{eventId}/tickets/{ticketId}/transitions` | ADMIN |
| `OrderRestController` | `Mono<ResponseEntity<OrderAcceptedResponseDto>> placeOrder(String eventId, String idempotencyKey, PlaceOrderRequestDto, ServerHttpRequest)` | `POST /api/v1/events/{eventId}/orders` → 202 + Location `/api/v1/orders/{id}` | CUSTOMER, ADMIN |
| | `Mono<OrderStatusResponseDto> getOrder(String orderId, ServerHttpRequest)` | `GET /api/v1/orders/{orderId}` | CUSTOMER (owner), ADMIN |
| | `Mono<ResponseEntity<OrderStatusResponseDto>> confirmOrder(String orderId, String idempotencyKey, ConfirmOrderRequestDto, ServerHttpRequest)` | `POST /api/v1/orders/{orderId}/confirmation` → 202 | CUSTOMER (owner) |
| | `Mono<ResponseEntity<OrderStatusResponseDto>> cancelOrder(String orderId, ServerHttpRequest)` | `DELETE /api/v1/orders/{orderId}/reservation` → 202 | CUSTOMER (owner) |
| `ComplimentaryRestController` | `Mono<ResponseEntity<OrderAcceptedResponseDto>> issueComplimentary(String eventId, String idempotencyKey, IssueComplimentaryRequestDto, ServerHttpRequest)` | `POST /api/v1/events/{eventId}/complimentary-tickets` → 202 | ADMIN |

Header `Idempotency-Key` is required on the POST endpoints; it must match `^[A-Za-z0-9-]{8,64}$` (`presentation.impl.idempotency.IdempotencyKeyResolver`, throws `DomainValidationException(INVALID_IDEMPOTENCY_KEY, ...)`). `RequestFingerprintCalculator` hashes a canonical string (`"place-order|" + eventId + "|" + quantity`, etc.) with `HashUtil.sha256Hex`.

Inbound channel controllers (no Spring Web annotations):
- `OrderReservationSqsController` / `OrderPaymentSqsController`: `Mono<Void> handle(String payload)`. Parse JSON (Jackson 3 `JsonMapper`) into `OrderReservationMessageRequestDto` / `PaymentRequestedMessageRequestDto`, map with `OrderMessageDtoMapper`, call the use case. Malformed payload → error signal (message goes to the DLQ after maxReceiveCount). Non-retryable `TicketingException` → log WARN and complete empty (acknowledge). Retryable → propagate error (no ack).
- `ReservationExpirationSchedulerController` / `OrderDispatchSchedulerController`: `Mono<SweepResult> execute()` delegating to the sweeper use cases, logging the result; errors are logged and converted to `SweepResult.skipped()`... (never error out the timer).

### 7.2 DTOs (`presentation.dto`, records with Jakarta Validation)
```java
CreateEventRequestDto(@NotBlank @Size(max=120) String name, @NotBlank @Size(max=120) String venue,
                      @NotNull @Future Instant eventDate, @Min(1) @Max(100000) int totalCapacity,
                      @NotNull @DecimalMin("0.00") @Digits(integer=8, fraction=2) BigDecimal ticketPrice,
                      @NotBlank @Pattern(regexp="^[A-Z]{3}$") String currency)
EventResponseDto(String eventId, String name, String venue, Instant eventDate, int totalCapacity, BigDecimal ticketPrice,
                 String currency, EventStatusEnum status, int availableTickets, Instant createdAt)
AvailabilityResponseDto(String eventId, int totalCapacity, int available, int reserved, int pendingConfirmation,
                        int sold, int complimentary, Instant capturedAt)
PlaceOrderRequestDto(@Min(1) @Max(10) int quantity)
IssueComplimentaryRequestDto(@NotBlank @Size(max=128) String beneficiaryId, @Min(1) @Max(40) int quantity)
ConfirmOrderRequestDto(@NotBlank @Size(max=128) @Pattern(regexp="^[A-Za-z0-9_-]+$") String paymentToken)
OrderAcceptedResponseDto(String orderId, TicketStatusEnum status, OrderProcessingStatusEnum processingStatus, String statusUrl)
OrderStatusResponseDto(String orderId, String eventId, OrderTypeEnum type, int quantity, TicketStatusEnum status,
                       OrderProcessingStatusEnum processingStatus, String reason, List<String> ticketIds,
                       Instant holdExpiresAt, Instant createdAt, Instant updatedAt)
TicketTransitionResponseDto(String ticketId, TicketStatusEnum fromStatus, TicketStatusEnum toStatus, String orderId,
                            TransitionReasonEnum reason, String actor, Instant occurredAt)
PageResponseDto<T>(List<T> items, String nextCursor)
message/OrderReservationMessageRequestDto(String orderId, String eventId, String correlationId, Instant occurredAt)
message/PaymentRequestedMessageRequestDto(String orderId, String paymentToken, String correlationId, Instant occurredAt)
```

### 7.3 Context, mappers, errors
- `presentation.context.RequestContext(String subject, Set<String> roles, String correlationId, String clientIp)` with `boolean isAdmin()`; `RequestContextResolver#resolve(ServerHttpRequest) : Mono<RequestContext>` (impl in `presentation.context.impl`) reads the JWT from `ReactiveSecurityContextHolder` (`sub`, authorities `ROLE_*`), `X-Correlation-Id` header (set by `CorrelationIdWebFilter`), client IP (first `X-Forwarded-For` hop, else remote address).
- Mappers (`@Component`): `EventDtoMapper`, `AvailabilityDtoMapper`, `OrderDtoMapper`, `OrderMessageDtoMapper`, `TicketTransitionDtoMapper`.
- `presentation.impl.error.GlobalErrorHandler` (`@RestControllerAdvice`): `TicketingException` → `ProblemDetail` (status from `ErrorCodeEnum`, `title` = code name, properties `errorCode`, `correlationId`, `timestamp`); `RateLimitExceededException` adds `Retry-After`; `WebExchangeBindException` → 400 VALIDATION_ERROR with `errors: [{field, message}]`; `ServerWebInputException`/`MethodArgumentTypeMismatch`-style input errors → 400; anything else → 500 INTERNAL_ERROR with a generic message (log the exception, never expose it). Content type `application/problem+json`.

---

## 8. Config (`com.ticketing.config`)

- `config.properties.TicketingProperties` (`@ConfigurationProperties("ticketing")`, nested records with `@DefaultValue`): `runtimeMode`, `orders{holdTtl, confirmationTimeout, republishAfter, maxTicketsPerOrder, maxComplimentaryPerRequest, candidateFactor}`, `events{maxCapacity, availabilityStreamInterval, ticketWriteConcurrency}`, `cache{eventTtl=2s, eventMaxSize=10000}`, `sweeper{interval=15s, lease=30s, batchSize=100, concurrency=8}`, `retry{inventory{...}, publish{...}}`, `rateLimit{enabled=true, capacity=20, refillTokens=20, refillPeriod=1s}`, `messaging{maxMessages=10, waitTimeSeconds=20, visibilityTimeoutSeconds=60, concurrency=16, pollers=2}`, `payment{baseUrl, connectTimeout=1s, responseTimeout=3s}`, `security{audience=ms-ticket, issuer, rolesClaim=roles}`, `migrations{enabled=false}`.
- `config.properties.AwsProperties` (`@ConfigurationProperties("aws")`): `region`, `endpointOverride` (optional, one endpoint per service: `dynamodbEndpoint`, `sqsEndpoint`), `dynamodb{tables{events,tickets,orders,ticketTransitions,distributedLocks,schemaMigrations}, deadlineShards=8}`, `sqs{orderReservationQueue, orderPaymentQueue}`. **No credentials in properties** — `DefaultCredentialsProvider` (env vars locally, IAM task role in AWS).
- `config.policy.DomainPolicyConfig` — beans `OrderPolicy`, `EventPolicy`, `SweeperPolicy` (instanceId = hostname + random suffix), `RetryPolicy` (qualifiers `inventoryRetryPolicy`, `publishRetryPolicy`).
- `config.time.ClockConfig` — `Clock.systemUTC()`, `RandomGenerator` (`RandomGenerator.getDefault()`).
- `config.aws.AwsClientConfig` — `DynamoDbAsyncClient`, `SqsAsyncClient` with `NettyNioAsyncHttpClient`, optional endpoint override, region.
- `config.dynamodb.DynamoDbConfig` — `DynamoDbEnhancedAsyncClient`, `DynamoDbTableNames` bean (record in `persistence.impl.support`), deadline shards.
- `config.migration.DynamoMigrationRunner`, `MigrationChangelogReader`.
- `config.messaging.SqsListenerContainer` (`SmartLifecycle`, only when `runtimeMode.workerEnabled()`): per queue N pollers: `receiveMessage(maxMessages, waitTimeSeconds, attributeNames ApproximateReceiveCount)` → `repeat()` while running → `flatMap(handler, concurrency)` → success ⇒ `deleteMessage`; failure ⇒ `changeMessageVisibility(min(2^receiveCount × 5s, 300s))` (exponential redelivery backoff) and keep going; poll errors ⇒ backoff retry forever. `stop()` drains in-flight messages (timeout 30s). `config.messaging.SqsListenerConfig` registers `SqsQueueListener(queueName, Function<String, Mono<Void>> handler)` for both SQS controllers. `config.client.SqsClientConfig` creates the `SqsQueueNames` bean.
- `config.scheduling.SchedulingConfig` — `SmartLifecycle` timer (`Flux.interval(sweeper.interval)`) calling both scheduler controllers sequentially; only when worker enabled.
- `config.security.SecurityConfig` — stateless `SecurityWebFilterChain`: CSRF/basic/form/logout disabled; `permitAll`: `GET /api/v1/events/**` except `.../tickets/**`, `/actuator/health/**`, `/actuator/info`, `/actuator/prometheus`; `hasRole('ADMIN')`: `POST /api/v1/events`, `/api/v1/events/*/complimentary-tickets`, `/api/v1/events/*/tickets/**`; `hasAnyRole('CUSTOMER','ADMIN')`: `/api/v1/orders/**`, `POST /api/v1/events/*/orders`; everything else authenticated. OAuth2 resource server (JWT, `jwk-set-uri`), issuer + audience validators, `JwtRoleConverter` (`roles` claim → `ROLE_*`). 401/403 rendered as `ProblemDetail`. Security headers (HSTS, no-sniff, frame deny).
- `config.web.WebFluxConfig` (codec max in-memory size 256 KB), `config.web.CorrelationIdWebFilter` (`X-Correlation-Id`, validated `^[A-Za-z0-9-]{1,64}$`, else generated; echoed in the response; stored as exchange attribute and Reactor context key `correlationId`).
- `config.ratelimit.RateLimitWebFilter` (order after security): Bucket4j token bucket per principal (else client IP), buckets kept in a bounded Caffeine cache; only `POST`/`DELETE` under `/api/`; 429 ProblemDetail + `Retry-After`.
- `config.resilience.ResilienceConfig` — `CircuitBreaker` named `paymentGateway` (failure rate 50 %, sliding window 20, wait 30 s, half-open 5 calls).
- `config.client.PaymentGatewayClientConfig` — `WebClient` for the payment gateway (Reactor Netty connect/response timeouts) + `PaymentGatewaySettings` record (in `client.rest.impl`).
- `config.cache.CacheConfig` — `EventCacheSettings` bean.
- `config.observability.ObservabilityConfig` — common metric tags (`application`, `runtime_mode`), `Hooks.enableAutomaticContextPropagation()`.

`application.yml` keys follow the property names above; profiles `local`, `dev`, `ci`, `prod`. `spring.threads.virtual.enabled=true` (only affects Spring task executors — see ADR-009). Actuator exposure: `health,info,prometheus`; health probes enabled; graceful shutdown (`server.shutdown=graceful`).

---

## 9. Client details
- `client.sdk.impl.SqsOrderQueueClientImpl` — `SqsAsyncClient`; queue URLs resolved once via `getQueueUrl` and cached (`Mono.cache()`); body = JSON of `client.dto.sqs.*MessageDto`; message attributes `messageType`, `correlationId`, `schemaVersion=1`; any failure → `MessagePublishingException`. Mapping in `client.mapper.SqsOrderQueueClientMapper`.
- `client.rest.impl.PaymentGatewayChargeClientImpl` — `POST {baseUrl}/v1/charges` (header `Idempotency-Key`), body `client.dto.payment.ChargeRequestDto(orderId, customerId, amount, currency, paymentToken)`, response `ChargeResponseDto(paymentId, status /*APPROVED|DECLINED*/, declineReason)`; `GET {baseUrl}/v1/charges/{idempotencyKey}` → 200 `ChargeResponseDto` or 404 → `NotFound`. Per-call timeout, retry 2× on 5xx/timeouts (backoff), then resilience4j `CircuitBreakerOperator`; errors → `PaymentGatewayException`. Mapping in `client.mapper.PaymentGatewayChargeClientMapper`.

---

## 10. Testing conventions
- Unit tests: `@ExtendWith(MockitoExtension.class)`, strict stubs, `StepVerifier` for every reactive path (including error and empty branches), `StepVerifier.withVirtualTime` for intervals/backoff. Fixed `Clock.fixed(...)`.
- Controllers: `@WebFluxTest(controllers = XxxRestControllerImpl.class)` + `@Import({SecurityConfig.class, JwtRoleConverter.class, GlobalErrorHandler.class, <mappers>, <context resolver>})`, `@MockitoBean` for use cases and `ReactiveJwtDecoder`, authenticate with `mockJwt().jwt(j -> j.subject("customer-1")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))`.
- Concurrency: `business.usecase.impl.inventory.ReserveTicketsUseCaseImplConcurrencyTest` uses an in-memory fake `InventoryTransactionPersistence`/`TicketPersistence`/`OrderPersistence` with atomic compare-and-set semantics and fires ≥1000 concurrent reservations at capacity 100 → exactly 100 tickets reserved, never a ticket in two orders, counters consistent. Plus races: confirm vs expiry, duplicate SQS delivery, same Idempotency-Key in parallel.
- Integration tests (`src/integrationTest/java`, suffix `IT`): Testcontainers `GenericContainer("amazon/dynamodb-local:latest")` and `LocalStackContainer(DockerImageName.parse("localstack/localstack:4.9"))` with SQS.
- Name test methods descriptively (`shouldRejectOrderWhenEventIsSoldOut`). No `Thread.sleep` in tests.

---

## 11. Clarifications after wave 1 (authoritative)

1. **Retry helper**: call `ReactiveRetryUtil.backoff(p.maxAttempts(), p.minBackoff(), p.maxBackoff(), p.jitter(), TicketingException::isRetryable)` (util cannot see `RetryPolicy`; `maxAttempts` counts *retries*). On exhaustion the last original error is propagated (not `RetryExhaustedException`).
2. **Two `RetryPolicy` beans** (`inventoryRetryPolicy`, `publishRetryPolicy`) are resolved **by constructor parameter name** (the build uses `-parameters`; business must not import `@Qualifier`). Name the parameters exactly like that.
3. **Ids**: `DeterministicIdUtil.nameBasedId(String)` returns the String UUID v5 used as `orderId`.
4. **Layer settings records** (created once, consumed by the layer, instantiated in `config`):
   - `persistence.impl.support.DynamoDbTableNames(events, tickets, orders, ticketTransitions, distributedLocks, int deadlineShards)`
   - `client.sdk.impl.SqsQueueNames(orderReservationQueue, orderPaymentQueue)`
   - `client.rest.impl.PaymentGatewaySettings(Duration callTimeout, int maxRetries, Duration retryBackoff)` (from `ticketing.payment.call-timeout|max-retries|retry-backoff`); the `WebClient` (base URL, connect/response timeouts) is a bean built in `config.client.PaymentGatewayClientConfig` and injected into the client impl.
   - `cache.impl.EventCacheSettings(Duration ttl, long maxSize)`
5. **Tickets GSI** `eventId-statusKey-index` projects `INCLUDE [status, version, updatedAt]`, so `findAvailable` builds full `TicketData` (orderId/holdExpiresAt are null for AVAILABLE tickets).
6. **Queue names** default to `ticketing-order-reservation` and `ticketing-order-payment` (DLQs: `ticketing-order-reservation-dlq`, `ticketing-order-payment-dlq`).
7. **`schema-migrations`** table is created by `DynamoMigrationRunner` itself (not a migration file); migration `TableName`s are kebab-case logical keys mapped to `AwsProperties.Tables` (`ticket-transitions` → `ticketTransitions`, `distributed-locks` → `distributedLocks`); `TimeToLiveSpecification` is applied with `UpdateTimeToLive` after `CreateTable`.
8. **Correlation id** constants: `RequestContext.CORRELATION_ID_HEADER = "X-Correlation-Id"`, `RequestContext.CORRELATION_ID_KEY = "correlationId"` (config may reference presentation).
9. **Idempotency-Key** header is declared `required = false` so a missing header reaches `IdempotencyKeyResolver` and yields `400 INVALID_IDEMPOTENCY_KEY`.
10. `domain.validation.DomainPreconditions` is a pure helper for record invariants; invariant violations inside domain records/commands throw `IllegalArgumentException`/`NullPointerException` (programming errors → 500), while client input is validated by DTOs and use cases (→ 400).
11. `RandomGenerator` bean delegates to `ThreadLocalRandom.current()` (thread-safe).
12. Mapper packages contain only `*Mapper` classes; helpers go to a `support` package. `persistence.entity` contains only `*Entity` classes (converters go elsewhere, e.g. `persistence.impl.support`).
13. Every non-exempt top-level production class needs `<SimpleName>Test` in the same test package (TestMirrorRulesTest), including settings records, listeners, filters and runners in `config`.
