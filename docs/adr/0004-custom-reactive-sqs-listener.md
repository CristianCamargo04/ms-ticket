# ADR-0004 — Listener SQS reactivo propio en lugar de Spring Cloud AWS

- **Estado:** Aceptada
- **Fecha:** 2026-10-10

## Contexto

Los workers consumen dos colas SQS. Se requiere: procesamiento **no bloqueante** coherente con
WebFlux, **backpressure** (no recibir más de lo que se puede procesar mientras corre el visibility
timeout), *ack* solo tras éxito, re-entrega con backoff, detección de mensajes venenosos y
**apagado ordenado** (drenar mensajes en vuelo ante `SIGTERM`, p. ej. una interrupción de Fargate
Spot). Spring Cloud AWS (`@SqsListener`) depende de su compatibilidad con Spring Boot 4 y oculta el
modelo de concurrencia.

## Decisión

Implementar `config.messaging.SqsListenerContainer` (un `SmartLifecycle`) sobre `SqsAsyncClient` y
Reactor. Por cada `SqsQueueListener` (nombre de cola + `Function<String, Mono<Void>>`):

1. Resuelve la URL con `GetQueueUrl` (reintento con backoff hasta que exista) y lee una vez el
   `maxReceiveCount` de la `RedrivePolicy`.
2. `pollers` (2) bucles de `ReceiveMessage` (long polling `waitTimeSeconds` 20, `maxMessages` 10,
   `ApproximateReceiveCount` y todos los atributos) que se repiten mientras el contenedor corre.
   Los bucles se combinan con *prefetch* 1: un poller solo vuelve a recibir cuando su lote anterior
   entró a un hueco libre → los mensajes recibidos y no iniciados nunca superan
   `pollers × maxMessages`.
3. `flatMap(handle, concurrency)` (16 por cola). Cada handler está acotado a
   **90 % del visibility timeout** (54 s) para liberar el hueco antes de que SQS re-entregue.
4. **Éxito** → `DeleteMessage` (2 reintentos, 200 ms). **Error o timeout** →
   `ChangeMessageVisibility(min(2^receiveCount × 5 s, 300 s))`: re-entrega con backoff exponencial
   (10 s, 20 s, 40 s, 80 s, 160 s). En la última entrega permitida por la redrive policy el resultado
   se marca `poison` (log ERROR); SQS lo mueve a la DLQ en la siguiente recepción.
5. Errores de las llamadas SQS (resolución, polling) → reintento infinito con backoff 1 s..30 s y
   log WARN como máximo cada 30 s por bucle.
6. El contexto Reactor del handler lleva `correlationId` (atributo del mensaje, o el `messageId`
   si falta o es inválido), `sqsMessageId` y `sqsReceiveCount`; el `correlationId` llega al MDC.
7. **Ciclo de vida**: misma fase que el *graceful shutdown* del servidor web. `stop(Runnable)` corta
   el polling (cancela el long poll en curso), drena lo recibido y en vuelo y, si no termina dentro
   del 90 % de `spring.lifecycle.timeout-per-shutdown-phase` (30 s → 27 s), cancela los handlers
   restantes (sus mensajes vuelven tras el visibility timeout).
8. Métricas: `sqs.messages{queue,outcome=acked|failed|poison}`,
   `sqs.message.processing{queue,outcome}` (timer) y `sqs.messages.in.flight{queue}` (gauge).

Solo se registra en modo `worker`/`all` (`WorkerModeCondition`, ver ADR-0008).

## Consecuencias

**Positivas**
- Control explícito de concurrencia, backpressure y *ack*; nada bloquea el event loop
  (la única espera bloqueante es el `stop()` síncrono exigido por `Lifecycle`, que Spring no usa).
- Sin dependencia de la madurez de Spring Cloud AWS sobre Boot 4.
- Probado con unit tests (`StepVerifier.withVirtualTime`) y con LocalStack real
  (`SqsListenerContainerIT`).

**Negativas**
- Código de infraestructura propio que hay que mantener (≈ 550 líneas, más ≈ 900 de test unitario).
- Sin extensión de visibilidad (*heartbeat*) para handlers largos: el timeout del handler lo
  sustituye (aceptable, los handlers duran milisegundos o segundos).

## Alternativas consideradas

- **Spring Cloud AWS `@SqsListener`**: menos código, pero menor control y dudas de compatibilidad.
- **`@Scheduled` + cliente síncrono**: bloquea hilos y no ofrece backpressure.
- **Lambda con event source mapping SQS**: buena opción serverless, pero rompe "una imagen, dos
  modos" y complica el entorno local.

## Referencias

- `config/messaging/SqsListenerContainer.java`, `SqsListenerSettings.java`, `SqsListenerConfig.java`,
  `SqsListenerMetrics.java`, `src/integrationTest/.../SqsListenerContainerIT.java`.
