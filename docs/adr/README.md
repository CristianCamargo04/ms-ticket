# Architecture Decision Records (ADR)

Cada ADR documenta una decisión relevante con su **contexto**, la **decisión** tomada, sus
**consecuencias** (positivas y negativas) y las **alternativas** descartadas. Todas están en estado
*Aceptada* y describen el sistema tal como está construido (ver también
[`docs/design/implementation-spec.md`](../design/implementation-spec.md), sección 12 "As-built deviations").

| # | Decisión | Tema |
|---|---|---|
| [0001](0001-layered-architecture.md) | Arquitectura en capas según la jerarquía de carpetas del proyecto | Estructura |
| [0002](0002-dynamodb-conditional-transactions.md) | DynamoDB con transacciones condicionales en lugar de locks | Concurrencia |
| [0003](0003-sqs-standard-idempotent-consumers.md) | SQS Standard (at-least-once) + consumidores idempotentes en lugar de FIFO | Mensajería |
| [0004](0004-custom-reactive-sqs-listener.md) | Listener SQS reactivo propio en lugar de Spring Cloud AWS | Mensajería |
| [0005](0005-deterministic-order-id.md) | Idempotencia con `orderId` determinístico (UUID v5) | Idempotencia |
| [0006](0006-dual-write-republisher.md) | Mitigación del dual-write con re-publicador en lugar de outbox | Consistencia |
| [0007](0007-jwt-resource-server-anti-idor.md) | JWT resource server (mock-oauth2 local / Cognito) y anti-IDOR | Seguridad |
| [0008](0008-runtime-modes.md) | Una imagen, modos de ejecución `api` / `worker` | Despliegue |
| [0009](0009-virtual-threads-and-java-25.md) | Virtual threads solo en executors de Spring; features de Java 25 usadas | Plataforma |
| [0010](0010-pregenerated-tickets-random-selection.md) | Entradas pre-generadas + selección de candidatas con inicio aleatorio | Inventario |
| [0011](0011-sparse-sharded-deadline-index.md) | Índice de deadlines disperso y fragmentado (shards) | Datos |
| [0012](0012-localstack-pinned-4-9.md) | LocalStack fijado en 4.9 | Entorno local |
| [0013](0013-archunit-enforced-architecture.md) | Arquitectura verificada con ArchUnit | Calidad |

Formato: título, estado, fecha, contexto, decisión, consecuencias, alternativas y referencias al
código. Los identificadores (clases, propiedades, endpoints) se mantienen en inglés.
