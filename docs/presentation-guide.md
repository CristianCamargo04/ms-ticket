# Guía para la reunión técnica (30–40 min)

Guion de la presentación: orden de la demo, comandos exactos, qué mostrar para cada requisito y
respuestas a las preguntas difíciles esperadas. Todo lo que se dice aquí está respaldado por el
código y por la documentación enlazada.

## 0. Preparación (antes de la reunión)

```bash
cp .env.example .env
docker compose up -d --build --wait           # ~2-3 min la primera vez
scripts/infra/verify-infra.sh                 # todo PASS
./gradlew test integrationTest                # reportes listos para mostrar
docker pull grafana/k6:1.3.0                  # evitar la descarga en vivo
```

Ventanas abiertas:

| Ventana | Contenido |
|---|---|
| Terminal 1 | comandos de la demo (bash; en Windows Git Bash con `export MSYS_NO_PATHCONV=1`) |
| Terminal 2 | SSE: `curl -N http://localhost:8080/api/v1/events/$EVENT/availability/stream` |
| Terminal 3 | `docker compose logs -f ticketing-worker` |
| Navegador | `README.md` y `docs/architecture.md` renderizados (Mermaid), `build/reports/jacoco/test/html/index.html` |
| IDE | `InventoryTransactionPersistenceImpl`, `TransactionCancellationTranslator`, `ReserveTicketsUseCaseImpl`, `SqsListenerContainer`, `src/test/java/com/ticketing/architecture/` |

Variables de la terminal 1 (requiere `jq`; alternativa sin `jq`: `scripts/curl/flows.sh <flujo>`):

```bash
API=http://localhost:8080
ADMIN=$(scripts/infra/get-token.sh admin)
C1=$(scripts/infra/get-token.sh customer-1)
C2=$(scripts/infra/get-token.sh customer-2)
json() { curl -s -H 'Content-Type: application/json' "$@"; }
```

## 1. Agenda

| Minuto | Bloque | Objetivo |
|---|---|---|
| 0–3 | Problema y solución | Los 5 problemas del enunciado y la respuesta a cada uno (tabla del README) |
| 3–8 | Arquitectura | C4, capas, regla de dependencias verificada por ArchUnit, modos `api`/`worker` |
| 8–20 | Demo en vivo | Compra completa, idempotencia, anti-IDOR, rechazo, cancelación, cortesía, errores, SSE |
| 20–27 | Concurrencia en profundidad | Transacción condicional, traducción de cancelaciones, carreras, tests, k6 |
| 27–31 | Resiliencia y seguridad | Reintentos, DLQ, circuit breaker, sweeper con lease, JWT, rate limit |
| 31–35 | IaC, operación y costos | Terraform, escalado, alarmas, costos |
| 35–40 | Limitaciones y preguntas | Lista honesta y qué haría en producción |

## 2. Guion

### 2.1 Problema y solución (0–3 min)

Mostrar la tabla inicial del README. Mensaje clave: **la API nunca toca el inventario al aceptar;
la base de datos decide cada carrera de forma atómica; todo lo que se reintenta es idempotente.**

### 2.2 Arquitectura (3–8 min)

1. `docs/architecture.md` §1–2: contexto y contenedores; una imagen, dos modos (ADR-0008).
2. §3: capas y regla `presentation → business.usecase → persistence|client|cache → domain`.
   Explicar que los consumidores SQS y el timer también son *controllers* de `presentation`.
3. Abrir `LayerDependencyRulesTest` y ejecutar:
   `./gradlew test --tests "com.ticketing.architecture.*"` (48 reglas). "Si alguien rompe una capa,
   el build falla".
4. §4: máquina de estados de la entrada y de la orden (por qué dos campos: `status` es lo que pide
   el enunciado, `processingStatus` expresa "en cola" o "rechazada").

### 2.3 Demo en vivo (8–20 min)

**a) Crear evento (RF1)**

```bash
EVENT=$(json -X POST $API/api/v1/events -H "Authorization: Bearer $ADMIN" \
  -d '{"name":"Demo Live","venue":"Sala A","eventDate":"2027-01-15T20:00:00Z","totalCapacity":20,"ticketPrice":50,"currency":"USD"}' | jq -r .eventId)
curl -s $API/api/v1/events/$EVENT/availability | jq
```

Arrancar el SSE en la terminal 2 (RF2): cada cambio posterior aparecerá ahí.

**b) Compra completa (RF3, RF4, RF6)**

```bash
KEY=$(uuidgen)
ORDER=$(json -X POST $API/api/v1/events/$EVENT/orders -H "Authorization: Bearer $C1" \
  -H "Idempotency-Key: $KEY" -d '{"quantity":2}' | jq -r .orderId)       # 202 QUEUED
curl -s $API/api/v1/orders/$ORDER -H "Authorization: Bearer $C1" | jq      # RESERVED / ACTIVE + holdExpiresAt
json -X POST $API/api/v1/orders/$ORDER/confirmation -H "Authorization: Bearer $C1" \
  -H "Idempotency-Key: $(uuidgen)" -d '{"paymentToken":"tok_approved"}' | jq .status   # PENDING_CONFIRMATION
curl -s $API/api/v1/orders/$ORDER -H "Authorization: Bearer $C1" | jq '.status,.processingStatus'   # SOLD, COMPLETED
TICKET=$(curl -s $API/api/v1/orders/$ORDER -H "Authorization: Bearer $C1" | jq -r '.ticketIds[0]')
curl -s $API/api/v1/events/$EVENT/tickets/$TICKET/transitions -H "Authorization: Bearer $ADMIN" | jq
```

Mostrar en la terminal 3 los logs del worker `Order ... holds tickets` y `Order ... payment
approved` (el `Order ... confirmed, payment requested` aparece en `docker compose logs ticketing-api`);
en la terminal 2 los contadores moviéndose; la auditoría con `actor` (`order-worker`, `customer-1`,
`payment-worker`).

**c) Idempotencia (reintentos maliciosos)**

```bash
json -X POST $API/api/v1/events/$EVENT/orders -H "Authorization: Bearer $C1" -H "Idempotency-Key: $KEY" -d '{"quantity":2}' | jq   # mismo orderId
json -X POST $API/api/v1/events/$EVENT/orders -H "Authorization: Bearer $C1" -H "Idempotency-Key: $KEY" -d '{"quantity":3}' | jq   # 422
```

**d) Anti-IDOR**: `curl -s $API/api/v1/orders/$ORDER -H "Authorization: Bearer $C2" | jq` → `404`.

**e) Pago rechazado, cancelación y cortesía (RF5, RF7)**

```bash
scripts/curl/flows.sh declined cancel complimentary
```

**f) Errores y abuso**

```bash
scripts/curl/flows.sh security ratelimit soldout
```

Señalar: `401`/`403` con `WWW-Authenticate`, `429` con `Retry-After`, `409 SOLD_OUT` en la admisión
y `REJECTED/SOLD_OUT` cuando la carrera se pierde en el consumidor; todo en `ProblemDetail`.

**g) Expiración del hold (RF5) — modo demo**

```bash
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --wait ticketing-api ticketing-worker
scripts/curl/flows.sh expiry          # RESERVED -> EXPIRED/HOLD_EXPIRED por el sweeper; confirmar -> 410
```

(Hold de 30 s y sweeper cada 5 s; al final volver a los tiempos reales con
`docker compose -f docker-compose.yml up -d --wait ticketing-api ticketing-worker`.)

### 2.4 Concurrencia en profundidad (20–27 min)

1. IDE: `InventoryTransactionPersistenceImpl.reserve` — los `2n + 2` ítems y sus condiciones
   (`#status = ON_SALE AND availableCount >= :q`, `#status = AVAILABLE`,
   `processingStatus = QUEUED`, auditoría `attribute_not_exists`).
2. `TransactionCancellationTranslator`: índice → `OrderStateChanged` / `InsufficientInventory` /
   `StaleTicketSelection` / conflicto reintentable.
3. `ReserveTicketsUseCaseImpl`: `switch` exhaustivo sobre el `sealed interface` + reintento con
   backoff y jitter; selección con inicio aleatorio (ADR-0010).
4. Diagramas §5.2 (conflicto y reintento) y §5.3 (confirmación vs expiración).
5. Tests: `ReserveTicketsUseCaseImplConcurrencyTest` (1 000 órdenes por 100 entradas, mensaje
   duplicado ×10, misma clave ×50, carrera en el límite del hold) e
   `InventoryTransactionPersistenceImplConcurrencyIT` (200 vs 50 en DynamoDB Local real).
6. **k6 en vivo** (300 compradores por 50 entradas):

   ```bash
   docker run --rm -i -v "$PWD/load-test/k6:/scripts:ro" \
     -e API_URL=http://host.docker.internal:8080 \
     -e AUTH_URL=http://host.docker.internal:8090/default -e AUTH_HOST=localhost:8090 \
     -e CAPACITY=50 -e BUYERS=300 grafana/k6:1.3.0 run /scripts/ticket-rush.js
   ```

   Mostrar `oversell_violations = 0`, `duplicate_tickets = 0` y que vendidas + retenidas ≤ capacidad.
   Opcional durante la carga: `docker compose up -d --wait --scale ticketing-worker=3`.

### 2.5 Resiliencia y seguridad (27–31 min)

- `docs/architecture.md` §8: qué se reintenta y dónde; backoff de re-entrega
  (10 s → 160 s) y DLQ tras 5 recepciones; diagrama §8.3.
- Pasarela caída: `scripts/curl/flows.sh payment-error` (con el modo demo); mostrar el circuit
  breaker y la conciliación (§5.5): **los pagos pendientes nunca se liberan a ciegas**.
- Dual-write: diagrama §5.6 (outbox lite).
- Seguridad: `docs/security.md` — JWT, anti-IDOR, rate limit, cabeceras, contenedor endurecido,
  tabla STRIDE.

### 2.6 IaC, operación y costos (31–35 min)

- `infra/terraform/README.md` §1 (diagrama AWS): ECS api/worker, ALB + WAF, DynamoDB, SQS, Cognito,
  KMS, endpoints de VPC.
- Escalado: API por CPU/peticiones; worker por *backlog per task* con Spot.
- `docs/operations.md`: alarmas (DLQ, edad del mensaje, 5xx, p99) con rollback de despliegues;
  runbooks; costos (transacciones = 2× WCU, endpoints vs NAT, Spot).
- `infra/terraform/scripts/validate.sh` (fmt, validate, tests offline, tflint, checkov).

### 2.7 Limitaciones (35–37 min)

Mostrar `docs/limitations-and-improvements.md` §1, §5, §10 y §12: partición caliente del evento,
outbox real, desalineaciones IaC ↔ app ya identificadas y el plan de producción.

## 3. Qué mostrar para cada requisito

| Requisito | Demo | Código / prueba |
|---|---|---|
| RF1 Eventos | 2.3 a | `CreateEventUseCaseImpl`, `EventPersistenceImplIT` |
| RF2 Disponibilidad en tiempo real | terminal 2 (SSE) | `StreamEventAvailabilityUseCaseImpl`, `AvailabilityRestControllerImpl` |
| RF3 Compra asíncrona sin sobreventa | 2.3 b, 2.4 (k6) | `PlaceOrderUseCaseImpl`, `ReserveTicketsUseCaseImpl`, `InventoryTransactionPersistenceImpl`, tests de concurrencia |
| RF4 Estado de la orden | 2.3 b, d | `GetOrderStatusUseCaseImpl`, `OrderDtoMapper` |
| RF5 Reservas con expiración | 2.3 g, e (cancelación) | `ReleaseExpiredReservationsUseCaseImpl`, `CancelOrderUseCaseImpl`, `OrderPersistenceImpl.findDueOrders` |
| RF6 Transiciones válidas, atómicas, auditables | 2.3 b (auditoría) | `TicketStatusEnum`, `TicketStateMachine`, ítems de auditoría de la transacción |
| RF7 Cortesías y confirmación/pago | 2.3 b, e | `IssueComplimentaryTicketsUseCaseImpl`, `ConfirmOrderUseCaseImpl`, `ProcessOrderPaymentUseCaseImpl` |
| Reactivo / Java 25 | 2.4 | `ReactiveRulesTest`, ADR-0009 |
| Tests ≥ 90 % | reporte JaCoCo | 1 477 tests, líneas 100 %, ramas 99,9 % |
| Docker | 0, 2.3 g | `Dockerfile`, `docker-compose.yml`, `docker-compose.demo.yml` |
| Seguridad | 2.3 c, d, f | `SecurityConfig`, `RateLimitWebFilter`, `docs/security.md` |
| IaC | 2.6 | `infra/terraform` |

## 4. Preguntas difíciles esperadas

**¿Por qué no un lock distribuido (Redis, DynamoDB lock) por evento?**
Serializa justo el recurso disputado, agrega latencia y deja locks huérfanos si una instancia muere.
Con transacciones condicionales la base decide atómicamente sin coordinación y sin estados
intermedios; el único lock (lease) que existe es para que los sweepers no hagan trabajo duplicado,
y la corrección no depende de él.

**¿Qué pasa con `TransactionConflict` en un pico?**
Se traduce a `InventoryConflictException` (reintentable): 6 reintentos locales con backoff
exponencial y jitter (25 ms..1 s) y nueva selección de entradas; si se agotan, el mensaje no se
borra y SQS lo re-entrega con backoff (10 s, 20 s, ...). Nunca se pierde ni se duplica la reserva.

**¿Cómo evitas vender dos veces si SQS entrega el mismo mensaje dos veces?**
La transacción de reserva exige `processingStatus = QUEUED` en la orden: la segunda entrega obtiene
`OrderStateChanged` y devuelve la orden ya reservada. Probado con 10 entregas concurrentes del mismo
mensaje.

**¿Y si la API guarda la orden pero falla al publicar en SQS?**
Se responde 202 igual (la orden es durable) y el sweeper de despacho re-publica las órdenes `QUEUED`
cuyo deadline (30 s) venció; el consumidor es idempotente. En producción usaría outbox con DynamoDB
Streams + EventBridge Pipes (ADR-0006). Limitación conocida: a los 10 min sin despacho la orden pasa
a `FAILED/DISPATCH_EXHAUSTED`.

**¿Por qué SQS Standard y no FIFO por evento?**
FIFO serializaría cada evento (menos conflictos pero menos throughput justo en el pico) y aun así
habría que ser idempotente (la deduplicación FIFO dura 5 min). Standard + consumidores idempotentes
escala horizontalmente.

**¿Qué pasa si el cliente confirma exactamente cuando vence el hold?**
Las dos transacciones condicionan el mismo ítem de orden (`ticketStatus = RESERVED AND
processingStatus = ACTIVE`): DynamoDB las serializa y gana exactamente una. Si gana la expiración, la
confirmación relee y responde `410 HOLD_EXPIRED` (diagrama §5.3, test repetido 10 veces).

**¿Y si se cobró pero el worker murió antes de marcar `SOLD`?**
El mensaje no fue borrado: se re-entrega, la pasarela devuelve el mismo resultado para la misma
`Idempotency-Key = orderId` (no cobra dos veces) y se finaliza. Si mientras tanto vence el deadline de
confirmación, la conciliación consulta el cobro (`GET /v1/charges/{orderId}`) y finaliza la venta.

**¿Y si el cobro termina después de que la conciliación liberó la orden?**
Caso extremo (un cobro iniciado antes del deadline que responde después de la consulta): se registra
un ERROR para reembolso manual y hay una alarma basada en ese log. En producción: autorizar al
confirmar y capturar al vender, con anulación automática.

**¿Cuál es el cuello de botella?**
El ítem del evento: todas las reservas lo escriben. Una partición soporta ~1 000 WCU/s y una
transacción cuesta 2× → cientos de reservas/s por evento. Más workers no ayudan; la solución es
fragmentar los contadores en N shards y, para *drops* masivos, una sala de espera virtual.

**¿Por qué WebFlux y no MVC con virtual threads?**
El enunciado pide una plataforma reactiva; además hay backpressure de punta a punta (consumidor SQS
con concurrencia acotada, SSE con `onBackpressureLatest`) y streaming. Los virtual threads no aportan
sobre un event loop que no bloquea; están habilitados solo para executors de Spring (ADR-0009, con
honestidad: casi sin uso).

**¿Por qué pre-generar las entradas?**
Cada entrada necesita estado propio, transiciones verificables y auditoría. El costo es escribir
`capacity` ítems al crear el evento (límite configurable de 20 000); el GSI con `statusKey` hace que
"entradas libres" sea un rango de clave.

**El GSI es eventualmente consistente, ¿no puede elegir una entrada ya tomada?**
Sí, y está previsto: la condición `#status = AVAILABLE` de la transacción lo detecta
(`StaleTicketSelection`) y se reintenta con otra selección. La consistencia la garantiza la
transacción, no la lectura.

**¿Cómo escalan los workers? ¿Y los sweepers con varias réplicas?**
Workers: por backlog de la cola (cada réplica consume en paralelo, concurrencia 16 por cola). Los
sweepers corren en todas las réplicas pero toman un lease condicional en `distributed-locks`: solo una
barre por ciclo; si muere, el lease expira en 30 s.

**¿Qué pasa si se reinicia un worker en medio de un mensaje?**
`SIGTERM` → el contenedor deja de recibir, drena lo que está en vuelo (hasta 27 s) y luego cancela;
lo no terminado vuelve a ser visible tras el visibility timeout y se reprocesa idempotentemente.

**¿Cómo evitas IDOR?**
El cliente es siempre el `sub` del JWT; leer/confirmar/cancelar exige ser el dueño y una orden ajena
devuelve 404 igual que una inexistente. La transacción de confirmación repite `customerId` como
condición.

**¿Cómo manejas los secretos?**
No hay credenciales en código ni YAML; `DefaultCredentialsProvider` (task role en AWS); `.env` local
con dummies `LOCAL_AWS_*`; Secrets Manager inyectado por ECS. Los tokens de pago nunca se loguean.

**¿Cómo pruebas la concurrencia de forma determinista?**
Fakes en memoria con compare-and-set atómico que reproducen las condiciones de DynamoDB, miles de
operaciones en paralelo sobre `Schedulers.parallel()`, `StepVerifier.withVirtualTime` para backoffs, y
el mismo escenario contra DynamoDB Local real en el IT; k6 lo valida de punta a punta.

**¿Qué pasa si se cae la pasarela?**
Timeout de 4 s por intento, 2 reintentos solo para 5xx/timeouts/I/O, circuit breaker (abre con 50 %
de fallos en 20 llamadas, 30 s). El mensaje se re-entrega con backoff y termina en la DLQ; las órdenes
cuyo deadline vence se concilian. Al volver la pasarela: re-encolar la DLQ (runbook en
`docs/operations.md`).

**¿Cuánto cuesta una orden?**
Una compra de 2 entradas escribe ~37 WRU en tablas (transacciones al doble) más los GSIs: del orden de
USD 0,00003 en DynamoDB on-demand y ~6 peticiones SQS. El costo fijo dominante en AWS son NAT,
endpoints y ALB/WAF (dev ≈ USD 120–150/mes).

**¿Hay algo que hoy no funcionaría en AWS?**
Sí, y está documentado (`docs/limitations-and-improvements.md` §10): el validador acepta solo `aud`
y los access tokens de Cognito traen `client_id` (habría que usar ID tokens o agregar un validador
`aud ∨ client_id`); además la API key de la pasarela inyectada por Terraform aún no se envía y el
filtro de una alarma difiere en mayúsculas. Son ajustes acotados.

**¿Qué cambiarías primero para producción?**
Las desalineaciones anteriores, outbox real con Streams + Pipes, contadores fragmentados + sala de
espera, rate limit distribuido con límite de órdenes activas por cliente, autorización/captura con
reembolsos automáticos y trazas exportadas con SLOs.
