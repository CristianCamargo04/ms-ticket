# ADR-0003 — SQS Standard (at-least-once) + consumidores idempotentes en lugar de FIFO

- **Estado:** Aceptada
- **Fecha:** 2026-10-10

## Contexto

La API debe responder rápido durante picos (sin timeouts) y el trabajo contendido (reservar en
DynamoDB, cobrar) debe hacerse a un ritmo controlado (*load leveling*). Se necesita una cola
durable. SQS ofrece dos tipos: **Standard** (throughput casi ilimitado, entrega *at-least-once*,
sin orden) y **FIFO** (orden por `MessageGroupId`, deduplicación de 5 min, throughput limitado
por grupo).

## Decisión

Usar **dos colas SQS Standard**, cada una con su DLQ:

| Cola | Productor | Consumidor | Caso de uso |
|---|---|---|---|
| `ticketing-order-reservation` | API (`PlaceOrder`, `IssueComplimentaryTickets`) y sweeper de despacho | worker | `ReserveTicketsUseCase` |
| `ticketing-order-payment` | API (`ConfirmOrder`) | worker | `ProcessOrderPaymentUseCase` |

Configuración local (`init-sqs.sh`) y AWS (módulo `sqs`): visibility timeout 60 s, long polling
20 s, retención 4 días, SSE, `RedrivePolicy` con `maxReceiveCount = 5` hacia
`<cola>-dlq` (retención 14 días, `RedriveAllowPolicy` restringida a su cola origen).

La garantía de "exactamente una vez" se obtiene **en el consumidor**, no en la cola:

- `ReserveTickets`: si la orden ya no está `QUEUED` devuelve el estado actual (entrega duplicada);
  la transacción exige `processingStatus = QUEUED`, así que dos entregas concurrentes no pueden
  reservar dos veces.
- `ProcessOrderPayment`: si la orden ya no está `PENDING_CONFIRMATION` devuelve el estado actual; el
  cobro usa `Idempotency-Key = orderId`, por lo que reintentos/duplicados nunca cobran dos veces.
- Error de negocio **no reintentable** → se registra y se hace *ack* (borrar el mensaje).
  Error **reintentable** o mensaje malformado → no hay *ack*: SQS lo re-entrega con backoff
  exponencial y finalmente lo mueve a la DLQ (ver ADR-0004).

## Consecuencias

**Positivas**
- Throughput sin límites prácticos y escalado horizontal de workers por *backlog per task*.
- Los mensajes son pequeños (`orderId`, `eventId`/`paymentToken`, `correlationId`, `occurredAt`):
  el estado real vive en DynamoDB, el mensaje es solo un disparador.
- Tolerancia a caídas: si un worker muere, el mensaje vuelve a ser visible tras el visibility
  timeout.

**Negativas**
- Duplicados y desorden posibles: obliga a que **todos** los consumidores sean idempotentes
  (probado en unit tests y en el IT de concurrencia).
- Sin orden por evento, varias reservas del mismo evento compiten en DynamoDB
  (`TransactionConflict` → reintentos).

## Alternativas consideradas

- **SQS FIFO con `MessageGroupId = eventId`**: serializa por evento (menos conflictos) pero limita
  el throughput por evento justo en el escenario de pico y no elimina la necesidad de idempotencia
  (la deduplicación FIFO dura 5 minutos).
- **Kinesis / Kafka (MSK)**: orden por partición y replay, pero más operación y costo para un flujo
  de comandos simple.
- **Procesamiento síncrono en la API**: es exactamente el problema actual (timeouts bajo picos).

## Referencias

- `src/main/resources/scripts/localstack/init-sqs.sh`, `infra/terraform/modules/sqs/main.tf`,
  `presentation/impl/OrderReservationSqsControllerImpl.java`,
  `presentation/impl/OrderPaymentSqsControllerImpl.java`.
