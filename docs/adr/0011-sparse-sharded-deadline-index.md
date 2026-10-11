# ADR-0011 — Índice de deadlines disperso y fragmentado (shards)

- **Estado:** Aceptada
- **Fecha:** 2026-10-10

## Contexto

Tres procesos dependen del tiempo: re-publicar órdenes `QUEUED` sin despachar, liberar reservas cuyo
*hold* de 10 min venció y conciliar pagos cuya confirmación superó 5 min. Un `Scan` periódico de la
tabla de órdenes crece con el histórico; un GSI por `status` concentraría todas las órdenes activas
en pocas particiones calientes.

## Decisión

La tabla `orders` tiene dos atributos opcionales que forman el GSI **`deadline-index`**
(PK `deadlinePartition`, SK `deadlineAt`, proyección `ALL`):

- `deadlinePartition = <DeadlineTypeEnum>#<shard>` con
  `shard = floorMod(orderId.hashCode(), aws.dynamodb.deadline-shards)` (8 por defecto);
- `deadlineAt` = instante (epoch ms) de la próxima acción.

| Estado de la orden | `DeadlineTypeEnum` | `deadlineAt` |
|---|---|---|
| `QUEUED` | `DISPATCH` | `createdAt + republishAfter` (30 s), re-programado en cada re-publicación |
| `ACTIVE` + `RESERVED` | `HOLD_EXPIRATION` | `holdExpiresAt` (10 min) |
| `ACTIVE` + `PENDING_CONFIRMATION` | `PAYMENT_CONFIRMATION` | `now + confirmationTimeout` (5 min) |
| terminal (`COMPLETED`, `REJECTED`, `EXPIRED`, ...) | — | atributos **eliminados** (`REMOVE`) |

Los atributos se escriben y eliminan **dentro de las mismas escrituras condicionales** que cambian
el estado (transacciones de inventario y `transitionProcessingStatus`), así que el índice nunca
contradice la orden. `findDueOrders(type, now, limit)` consulta los 8 shards en paralelo
(`deadlinePartition = :p AND deadlineAt <= :now`), fusiona, ordena por deadline y devuelve como
máximo `limit` (100) en total.

## Consecuencias

**Positivas**
- **Índice disperso**: solo contiene órdenes que necesitan una acción futura; el costo del barrido
  depende de lo pendiente, no del histórico.
- Los shards reparten escrituras y lecturas del GSI entre particiones.
- Un único mecanismo para despacho, expiración y conciliación.

**Negativas**
- El GSI es eventualmente consistente: una orden puede aparecer vencida cuando ya cambió. Es
  inocuo porque cada acción del sweeper es condicional (p. ej. liberar exige
  `holdExpiresAt <= :now` y `processingStatus = ACTIVE`).
- 8 consultas por tipo y ciclo aunque no haya trabajo (costo bajo con on-demand).
- Cambiar el número de shards con órdenes activas requiere migración (las órdenes existentes
  quedarían en shards no consultados).

## Alternativas consideradas

- **TTL de DynamoDB + Streams**: el borrado por TTL no es puntual (puede tardar horas) y borra el
  ítem; no sirve para un hold de 10 min.
- **EventBridge Scheduler / SQS con delay por orden**: un temporizador por orden (delay SQS máximo
  15 min) — viable, pero más piezas y más difícil de reconciliar si se pierde un temporizador.
- **GSI por `processingStatus`**: particiones calientes y lectura de órdenes no vencidas.

## Referencias

- `persistence/impl/OrderPersistenceImpl.java` (`findDueOrders`),
  `persistence/impl/support/DeadlinePartitioner.java`, `domain/data/OrderData.java`
  (`deadlineType()`), `db/migrations/20261010100200_TCK-003/create-orders-table.json`.
