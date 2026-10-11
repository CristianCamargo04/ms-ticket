# ADR-0005 — Idempotencia con `orderId` determinístico (UUID v5)

- **Estado:** Aceptada
- **Fecha:** 2026-10-10

## Contexto

Los clientes reintentan (doble clic, timeouts de red, reintentos automáticos de apps móviles) y un
atacante puede repetir peticiones a propósito. Una compra repetida no debe crear dos órdenes ni
retener entradas dos veces. Las soluciones clásicas guardan la clave en una tabla aparte con un
estado `IN_PROGRESS`, lo que agrega una escritura y casos borde (claves huérfanas, expiración).

## Decisión

- Los `POST` que crean órdenes (`/events/{eventId}/orders` y `/events/{eventId}/complimentary-tickets`)
  exigen la cabecera **`Idempotency-Key`** (`^[A-Za-z0-9-]{8,64}$`; ausente o inválida →
  `400 INVALID_IDEMPOTENCY_KEY`).
- El identificador de la orden es **determinístico**:
  `orderId = UUIDv5(customerId + ":" + idempotencyKey)` (`DeterministicIdUtil.nameBasedId`). Para
  cortesías se usa el administrador emisor: `UUIDv5(issuedBy + ":" + idempotencyKey)`. El
  `customerId` sale siempre del `sub` del JWT, por lo que dos clientes nunca colisionan.
- Se calcula una **huella** del payload (`RequestFingerprintCalculator`: SHA-256 de
  `"place-order|eventId|quantity"` o `"issue-complimentary|eventId|beneficiaryId|quantity"`, con
  `|` y `\` escapados) y se guarda en la orden como `requestHash`.
- `QueuedOrderAdmission.admit`:
  1. Si la orden ya existe y coincide (huella, evento, dueño, tipo y cantidad) se devuelve la misma
     (si sigue `QUEUED` re-publica su mensaje); si no coincide → `422 IDEMPOTENCY_KEY_REUSED`.
  2. Si no existe se inserta con `attribute_not_exists(orderId)`; si una petición concurrente ganó la
     inserción, se relee y se aplica el punto 1.
- La **confirmación** es idempotente por estado (una orden ya `PENDING_CONFIRMATION` re-publica el
  mensaje de pago; una `SOLD` se devuelve). Su `Idempotency-Key` se valida por consistencia de API,
  pero no se reenvía: hacia la pasarela de pagos la clave de idempotencia es el `orderId`.

## Consecuencias

**Positivas**
- Sin tabla extra ni estado `IN_PROGRESS`: la propia orden es el registro de idempotencia.
- Respuesta idéntica para reintentos (mismo `orderId`, `Location` y estado actual).
- La idempotencia se encadena de punta a punta: API (`orderId`) → cola (consumidor idempotente) →
  pasarela (`Idempotency-Key = orderId`).

**Negativas**
- La clave no expira: reutilizarla para otra compra del mismo cliente devuelve 422 para siempre (es
  el comportamiento esperado de una clave de idempotencia; el cliente genera una por intento de
  compra).
- La huella no incluye al cliente (ya forma parte del `orderId`).

## Alternativas consideradas

- **Tabla `idempotency-keys` con TTL y estado** (patrón de Stripe): más flexible (cachea la
  respuesta), pero agrega una escritura y una máquina de estados.
- **Deduplicación en la cola (SQS FIFO)**: solo 5 minutos y no evita la doble orden en la base.

## Referencias

- `business/usecase/impl/order/QueuedOrderAdmission.java`, `util/DeterministicIdUtil.java`,
  `presentation/impl/idempotency/IdempotencyKeyResolver.java`,
  `presentation/impl/idempotency/RequestFingerprintCalculator.java`.
