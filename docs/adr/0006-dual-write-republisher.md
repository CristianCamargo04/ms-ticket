# ADR-0006 — Mitigación del dual-write con re-publicador en lugar de outbox

- **Estado:** Aceptada
- **Fecha:** 2026-10-10

## Contexto

Aceptar una orden implica **dos escrituras en sistemas distintos**: el `PutItem` de la orden
(`QUEUED`) en DynamoDB y el `SendMessage` a SQS. No existe transacción común: si la publicación
falla después de guardar la orden (o la instancia muere entre ambas), la orden queda `QUEUED` sin
mensaje y nunca se procesaría. Lo mismo ocurre con el mensaje de pago tras confirmar.

## Decisión

**Outbox "lite" basado en deadlines** (`RepublishStaleOrdersUseCase`), sin infraestructura extra:

1. La orden nace con `deadlineAt = now + republishAfter` (30 s) en la partición `DISPATCH` del
   índice de deadlines (ADR-0011).
2. La API publica con reintentos (`ticketing.retry.publish`: 3 reintentos, 100 ms..1 s); si aun así
   falla **solo registra un WARN y responde 202**: la orden ya es durable.
3. El sweeper `order-dispatch-sweeper` (cada 15 s, bajo lease distribuido) busca órdenes `QUEUED`
   con deadline vencido, **re-publica** su `OrderReservationMessage` (`correlationId = orderId`) y
   mueve el deadline otros 30 s (`rescheduleDeadline`, condicional a `QUEUED`). La re-publicación es
   segura porque el consumidor es idempotente (ADR-0003).
4. Los reintentos son acotados: una orden que sigue `QUEUED` **20 periodos** después de creada
   (10 min con los valores por defecto) pasa a `FAILED` con motivo `DISPATCH_EXHAUSTED` y log ERROR.
5. Para el pago: si el `PaymentRequestedMessage` no se publica, la orden queda
   `PENDING_CONFIRMATION`; el cliente puede reconfirmar (re-publica) y, vencido el deadline de
   confirmación (5 min), el **sweeper de conciliación** consulta el cobro en la pasarela y finaliza
   o libera (ver `ReleaseExpiredReservationsUseCase`).

## Consecuencias

**Positivas**
- Ninguna orden queda huérfana; sin servicios adicionales (local y AWS iguales).
- El mismo índice que sirve a las expiraciones sirve al despacho: una sola consulta por shard.

**Negativas**
- Latencia de recuperación de hasta `republishAfter + intervalo del sweeper` (≈ 30–45 s) en el caso
  de fallo.
- Duplicados: con un *backlog* de cola mayor a 30 s el sweeper re-publica órdenes cuyo mensaje
  original aún espera (acotado a `batchSize` = 100 órdenes por ciclo; el consumidor los descarta).
- El corte de 10 min (`DISPATCH_EXHAUSTED`) puede marcar como fallidas órdenes legítimas si el
  *backlog* supera ese tiempo (por ejemplo, todos los workers detenidos >10 min). Ver
  [limitaciones](../limitations-and-improvements.md).

## Alternativas consideradas

- **Transactional outbox con DynamoDB Streams + EventBridge Pipes → SQS** (recomendado para
  producción): la publicación nace del propio cambio de la tabla, sin dual-write. Requiere Streams,
  Pipes y un formato de evento; no es reproducible igual en local.
- **Escribir primero en SQS y que el worker cree la orden**: la API no podría responder el estado ni
  garantizar la idempotencia de forma síncrona.
- **Transacción distribuida / 2PC**: no soportada entre DynamoDB y SQS.

## Referencias

- `business/usecase/impl/inventory/RepublishStaleOrdersUseCaseImpl.java`,
  `business/usecase/impl/order/QueuedOrderAdmission.java`,
  `business/usecase/impl/order/ConfirmOrderUseCaseImpl.java`.
