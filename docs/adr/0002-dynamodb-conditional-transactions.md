# ADR-0002 — DynamoDB con transacciones condicionales en lugar de locks

- **Estado:** Aceptada
- **Fecha:** 2026-10-10

## Contexto

Los problemas a resolver son **sobreventa** (vender más entradas que la capacidad) y **doble
asignación** (la misma entrada en dos órdenes) bajo picos de demanda, con varias instancias de API y
de worker procesando en paralelo. Un lock pesimista (por evento o por asiento) serializa el
tráfico, introduce esperas y deja locks huérfanos si una instancia muere.

## Decisión

Toda transición de inventario es **un único `TransactWriteItems`** de DynamoDB
(`InventoryTransactionPersistenceImpl`) cuyos ítems son **todos condicionales**. Para una orden de
`n` entradas el orden de los ítems es fijo:

| Índice | Ítem | Operación |
|---|---|---|
| `[0]` | evento (contadores) | `Update` con condición sobre `#status`/`availableCount` o `attribute_exists(eventId)` |
| `[1..n]` | cada entrada | `Update` con condición `#status = :from` (y `orderId = :orderId` si ya está retenida) |
| `[n+1]` | orden | `Update` con condición sobre `processingStatus`/`ticketStatus` (+ guardas de tiempo) |
| `[n+2..2n+1]` | auditoría (`ticket-transitions`) | `Put` con `attribute_not_exists(ticketKey)` |

Ejemplo — **reserva**: el evento exige `#status = ON_SALE AND availableCount >= :q` y hace
`availableCount -= q, reservedCount += q`; cada entrada exige `#status = AVAILABLE`; la orden exige
`processingStatus = QUEUED`. O se aplican los `2n + 2` ítems o ninguno.

Los motivos de cancelación de la transacción se traducen **por índice**
(`TransactionCancellationTranslator`) a un resultado de dominio sellado (`InventoryWriteOutcome`):

| Falla | Resultado | Reacción del caso de uso |
|---|---|---|
| condición de la orden | `OrderStateChanged` | otra entrega/actor ya la procesó → releer y devolver |
| condición del evento | `InsufficientInventory` | rechazar la orden `REJECTED / SOLD_OUT` |
| condición de una entrada | `StaleTicketSelection` | `InventoryConflictException` (retryable) → nueva selección |
| `TransactionConflict`, throttling, 5xx, timeouts | — | `InventoryConflictException` (retryable) con backoff + jitter |

Otras piezas de la decisión: cada `Update` hace `SET updatedAt = :now ADD version :one`; el
`ClientRequestToken` es determinístico por comando (idempotencia de los reintentos del SDK); los
contadores del evento solo cambian dentro de estas transacciones; las lecturas que deciden
(`findById` de evento y orden) son **fuertemente consistentes**.

## Consecuencias

**Positivas**
- Imposible vender dos veces la misma entrada o superar la capacidad: la base decide cada carrera
  de forma atómica, sin locks ni coordinación entre instancias.
- Escala horizontalmente: más workers no requieren más coordinación.
- Auditoría atómica: no existe una transición sin su registro en `ticket-transitions`.

**Negativas**
- El ítem del evento recibe **todas** las reservas de ese evento (partición caliente:
  ~1 000 WCU/s por partición y una transacción cuesta 2× WCU → del orden de cientos de
  reservas/s por evento). Mitigación futura: contadores fragmentados
  (ver [limitaciones](../limitations-and-improvements.md)).
- Las transacciones cuestan el doble de capacidad y están limitadas a 100 ítems
  (`2n + 2 ≤ 100` → `InventoryCommand.MAX_TICKETS_PER_TRANSACTION = 49`; el límite configurado es 10
  por compra y 40 por cortesía).
- Bajo contención aparecen `TransactionConflict`: se absorben con reintentos locales acotados
  (`ticketing.retry.inventory`: 6 reintentos, 25 ms..1 s, jitter 0.5) y luego con la re-entrega SQS.

## Alternativas consideradas

- **Lock pesimista distribuido** (por evento/asiento): serializa, agrega latencia y riesgo de locks
  huérfanos.
- **Optimistic locking solo con `version`** sobre cada ítem por separado: no da atomicidad entre
  evento, entradas y orden.
- **Base relacional con `SELECT ... FOR UPDATE SKIP LOCKED`**: válida, pero el enunciado pide
  DynamoDB y el modelo serverless/on-demand encaja mejor con picos.

## Referencias

- `persistence/impl/InventoryTransactionPersistenceImpl.java`,
  `persistence/impl/support/TransactionCancellationTranslator.java`,
  `domain/inventory/InventoryWriteOutcome.java`,
  `src/integrationTest/.../InventoryTransactionPersistenceImplConcurrencyIT.java`.
