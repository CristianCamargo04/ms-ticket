# ADR-0001 — Arquitectura en capas según la jerarquía de carpetas del proyecto

- **Estado:** Aceptada
- **Fecha:** 2026-10-10

## Contexto

El proyecto debe seguir una jerarquía de carpetas obligatoria:
`presentation`, `business/usecase`, `domain`, `persistence`, `client`, `cache`, `config`, `enums`,
`util`, con sufijos y dirección de dependencias fijados. Además el sistema tiene **tres canales de
entrada** (REST, colas SQS y un temporizador de barrido) y **tres adaptadores de salida** (DynamoDB,
SQS/pasarela de pagos y caché), y la lógica crítica (reservas concurrentes) debe poder probarse sin
infraestructura.

## Decisión

Adoptar la jerarquía como una **arquitectura limpia / hexagonal** con la regla de dependencias

```
presentation → business.usecase (interfaces) → persistence | client | cache (interfaces) → domain
config = composition root (puede depender de todo; nada depende de config)
```

| Capa | Rol | Ejemplos |
|---|---|---|
| `presentation` | Adaptadores de **entrada**: REST, consumidores SQS y canal del temporizador | `OrderRestControllerImpl`, `OrderReservationSqsControllerImpl`, `ReservationExpirationSchedulerControllerImpl` |
| `business.usecase` | Un caso de uso = una acción de negocio (15 interfaces `*UseCase` + `*UseCaseImpl`) | `ReserveTicketsUseCaseImpl`, `ConfirmOrderUseCaseImpl` |
| `domain` | Modelo puro (records, sealed interfaces, excepciones, políticas) sin Spring/Reactor/AWS | `OrderData`, `InventoryWriteOutcome`, `TicketStateMachine` |
| `persistence` / `client` / `cache` | Adaptadores de **salida** detrás de puertos (interfaces en el paquete raíz) | `InventoryTransactionPersistenceImpl`, `PaymentGatewayChargeClientImpl`, `EventCacheServiceImpl` |
| `config` | Raíz de composición: beans, seguridad, listener SQS, timer, filtros web | `SecurityConfig`, `SqsListenerContainer`, `MaintenanceTimer` |

Reglas adicionales aplicadas:

- Toda interfaz en el paquete padre y su implementación en `impl/` con sufijo `Impl`
  (`client.rest.api.XxxApi` → `client.rest.impl.XxxClientImpl`).
- Cada capa recibe su configuración como **records propios** (`DynamoDbTableNames`,
  `SqsQueueNames`, `PaymentGatewaySettings`, `EventCacheSettings`, `OrderPolicy`, ...) instanciados
  en `config`: ninguna capa lee clases de `config`.
- Los canales SQS y el temporizador también son "controllers" de `presentation`
  (`*SqsController`, `*SchedulerController`): la infraestructura que los dispara
  (`SqsListenerContainer`, `MaintenanceTimer`) vive en `config` y solo conoce sus interfaces.
- Las subcarpetas `channel/` y `provider/` de `business/usecase` no se crearon (YAGNI: no hay
  variantes reales por canal ni por proveedor).

## Consecuencias

**Positivas**
- La lógica de negocio se prueba con mocks/fakes en memoria (por ejemplo
  `ReserveTicketsUseCaseImplConcurrencyTest`), sin Docker.
- Cambiar DynamoDB, SQS o la pasarela afecta solo a su adaptador.
- La regla de dependencias es verificable automáticamente (ADR-0013).

**Negativas**
- Más tipos (DTO ↔ dominio ↔ entity) y mappers que en un diseño "anémico".
- `config` concentra mucho cableado; se mitiga con subpaquetes por tema (`security`, `messaging`,
  `scheduling`, `ratelimit`, ...).

## Alternativas consideradas

- **Paquetes por feature** (`order/`, `event/`): más cohesión por funcionalidad, pero contradice la
  jerarquía exigida.
- **Hexagonal con nombres `port`/`adapter`**: equivalente en esencia; se descartó para respetar los
  nombres y sufijos del enunciado.

## Referencias

- `docs/design/implementation-spec.md` §1,
  `docs/design/architecture-rules.md`, `src/test/java/com/ticketing/architecture/`.
