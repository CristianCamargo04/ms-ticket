# ADR-0009 — Virtual threads solo en executors de Spring; features de Java 25 usadas

- **Estado:** Aceptada
- **Fecha:** 2026-10-10

## Contexto

El stack es Java 25 + Spring Boot 4.1.1 + WebFlux. Java 21+ ofrece **virtual threads**, que hacen
barato el estilo bloqueante. La pregunta en una entrevista es inevitable: ¿por qué WebFlux y no
Spring MVC con virtual threads?, ¿se usan los virtual threads?

## Decisión

1. **El camino de la petición y de los mensajes es 100 % reactivo/no bloqueante**: Reactor Netty
   (servidor y `WebClient`), AWS SDK v2 asíncrono sobre Netty (`DynamoDbAsyncClient`,
   `SqsAsyncClient`), Caffeine `AsyncCache`, timers de Reactor (`Flux.interval`) para los sweepers.
   Una regla ArchUnit prohíbe `block*`, `Thread.sleep` y `Future.get/join` fuera de `config`.
2. **No se mezclan virtual threads en ese camino**: no aportan nada sobre un event loop que no
   bloquea y obligarían a cambiar de modelo a mitad de pipeline (`publishOn`/`subscribeOn`).
3. `spring.threads.virtual.enabled: true` está activo solo para los **executors administrados por
   Spring** (`applicationTaskExecutor`, scheduler de tareas). Honestamente: en esta aplicación casi
   no tienen uso (no hay `@Async` ni `@Scheduled`; los sweepers corren en `Schedulers.parallel()`),
   pero quedan listos para trabajo bloqueante residual futuro sin consumir hilos de plataforma.
4. El único código bloqueante permitido está en `config` y solo al arrancar/parar: el
   `DynamoMigrationRunner` (local/ci) y el `stop()` síncrono del `SqsListenerContainer`.

### Features de Java usadas (verificables en el código)

| Feature | Dónde |
|---|---|
| `record` (DTOs, datos de dominio, comandos, settings) | `domain.data.*`, `domain.inventory.*Command`, `presentation.dto.*`, `*Settings` |
| `sealed interface` + records | `InventoryWriteOutcome`, `PaymentOutcome`, `InventoryCommand` |
| `switch` exhaustivo con **record patterns** | `case Applied(OrderData applied) -> ...` en los casos de uso de inventario/orden |
| Pattern matching de tipos en `switch` (`case null, default`) | `JwtRoleConverter.authoritiesOf`, `PaymentGatewayChargeClientImpl.isTransientFailure` |
| **Variables sin nombre `_`** (Java 22+) | `case InsufficientInventory _ ->`, lambdas `_ -> ...`, `catch (Exception _)` |
| `Math.clamp`, `Math.ceilDiv` | `ListEventsUseCaseImpl`, `CreateEventUseCaseImpl` |
| `java.util.random.RandomGenerator` (bean inyectable) | `ClockConfig` (delegado a `ThreadLocalRandom`) |
| `HexFormat` | `DomainPolicyConfig.instanceId` |

No se usan (decisión consciente): `StructuredTaskScope`/`ScopedValue` (el paralelismo y el contexto
ya los resuelven Reactor y su `Context`), *flexible constructor bodies* y *stream gatherers*.

## Consecuencias

**Positivas**
- Un único modelo de concurrencia, con backpressure de punta a punta y pocos hilos.
- El compilador garantiza exhaustividad de los resultados de inventario y de pago (sealed + switch).

**Negativas**
- Curva de aprendizaje de Reactor y depuración más difícil (se mitiga con `correlationId` en el
  contexto Reactor y propagación automática al MDC).
- `spring.threads.virtual.enabled` tiene poco efecto práctico hoy; se documenta para no
  sobre-venderlo.

## Alternativas consideradas

- **Spring MVC + virtual threads**: código imperativo más simple y rendimiento similar para I/O;
  se descartó porque el enunciado pide una plataforma reactiva y por el streaming SSE y el
  backpressure del consumidor SQS.
- **Mezclar** (WebFlux + `publishOn(boundedElastic)` para lógica bloqueante): innecesario, no hay
  librerías bloqueantes en el camino crítico.

## Referencias

- `src/main/resources/application.yml` (`spring.threads.virtual.enabled`),
  `src/test/java/com/ticketing/architecture/ReactiveRulesTest.java`, `config/time/ClockConfig.java`.
