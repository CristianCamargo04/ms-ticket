# ADR-0010 — Entradas pre-generadas + selección de candidatas con inicio aleatorio

- **Estado:** Aceptada
- **Fecha:** 2026-10-10

## Contexto

Cada entrada debe tener su propio estado (`AVAILABLE`, `RESERVED`, `PENDING_CONFIRMATION`, `SOLD`,
`COMPLIMENTARY`), transiciones válidas y auditoría por entrada. Con muchos workers reservando a la
vez, si todos eligen "las primeras entradas libres" chocan constantemente sobre los mismos ítems
(`TransactionConflict` / condiciones fallidas).

## Decisión

**Pre-generación** (`CreateEventUseCaseImpl`):
- El evento se crea `PUBLISHING`; se escriben `totalCapacity` entradas `AVAILABLE` con ids
  `T-000001`… (`TicketData.ticketIdFor`) en `ticketWriteConcurrency` (4) **tramos contiguos en
  paralelo**, cada tramo con `BatchWriteItem` de 25 ítems y reintento de `UnprocessedItems`
  (8 intentos, 50 ms..2 s). Solo si se escribieron exactamente `capacity` entradas el evento pasa
  condicionalmente `PUBLISHING → ON_SALE`; si algo falla queda `PUBLISHING` y nunca acepta órdenes.
- Capacidad máxima configurable: `ticketing.events.max-capacity` = 20 000 (el DTO admite hasta
  100 000 y el caso de uso aplica el límite de negocio).

**Selección de candidatas** (`ReserveTicketsUseCaseImpl`):
1. Lectura consistente del evento; si `availableCount < quantity` → `REJECTED / SOLD_OUT` sin
   tocar entradas.
2. Inicio aleatorio `T-<random(1..capacity)>` y consulta al GSI `eventId-statusKey-index`
   (`statusKey BETWEEN "AVAILABLE#<inicio>" AND "AVAILABLE$"`), con ventana
   `quantity × candidateFactor` (3). Si no alcanza, segunda consulta desde el principio.
3. Se descartan candidatas que no estén `AVAILABLE` (la proyección del GSI trae `status`), se
   deduplican y se toma una **muestra aleatoria uniforme** (Fisher-Yates parcial) de `quantity`,
   ordenada.
4. Transacción condicional (ADR-0002). Si una entrada ya no estaba libre → `StaleTicketSelection` →
   reintento con backoff y **nueva** selección. Si no hay suficientes candidatas por retraso del
   índice → `InventoryConflictException` (reintento).

## Consecuencias

**Positivas**
- Estado y auditoría por entrada, con transiciones verificables.
- Los workers concurrentes rara vez eligen las mismas entradas → menos conflictos.
- El GSI con `statusKey = <STATUS>#<ticketId>` convierte "entradas libres" en un rango de clave
  (sin `FilterExpression` que desperdicie lectura) y la proyección `INCLUDE`
  (`status`, `version`, `updatedAt`) evita una segunda lectura.

**Negativas**
- Crear un evento grande escribe `capacity` ítems (20 000 entradas ≈ 800 `BatchWriteItem`): la
  petición `POST /events` tarda más con capacidades altas.
- El GSI es **eventualmente consistente**: puede devolver entradas recién tomadas; se absorbe con la
  condición de la transacción y el reintento.
- No hay asientos numerados por zona/fila: las entradas son de admisión general.

## Alternativas consideradas

- **Solo contador** (sin ítems por entrada): más barato, pero sin estado ni auditoría por entrada.
- **Generación perezosa** de entradas al reservar: evita la escritura inicial, pero complica la
  unicidad y la auditoría.
- **Selección secuencial** (primeras libres): máxima contención entre workers.

## Referencias

- `business/usecase/impl/event/CreateEventUseCaseImpl.java`,
  `business/usecase/impl/inventory/ReserveTicketsUseCaseImpl.java`,
  `persistence/impl/TicketPersistenceImpl.java`, `persistence/impl/support/BatchRetryPolicy.java`,
  `ReserveTicketsUseCaseImplConcurrencyTest`.
