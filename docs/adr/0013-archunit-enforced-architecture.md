# ADR-0013 — Arquitectura verificada con ArchUnit

- **Estado:** Aceptada
- **Fecha:** 2026-10-10

## Contexto

Las reglas de la jerarquía (dirección de dependencias, sufijos, `impl/`, tests espejo) se degradan
rápido si solo viven en un documento, sobre todo con varios contribuyentes trabajando en paralelo.

## Decisión

Las reglas son **tests ejecutables** en `src/test/java/com/ticketing/architecture/` (ArchUnit
1.5.1), que corren en cada `./gradlew test` (8 clases, 48 tests):

| Test | Qué hace cumplir |
|---|---|
| `LayerDependencyRulesTest` | Matriz de dependencias entre capas; `business` solo usa puertos (interfaces raíz de `persistence`, `client.sdk`, `client.rest.api`, `cache`); nada depende de `config` |
| `DomainPurityRulesTest` | `domain` solo depende de `java..`, `domain..`, `enums..` (sin Spring, Reactor, AWS, Jackson) |
| `EncapsulationRulesTest` | `entity`, DTOs de `client` y de `presentation` no salen de su capa |
| `ImplPlacementRulesTest` | `*Impl` en `..impl..` implementando una interfaz de un paquete no-impl; cada puerto y caso de uso tiene implementación |
| `NamingConventionRulesTest` | Sufijos `UseCase`, `Persistence`, `Entity`, `Controller`, `RequestDto`/`ResponseDto`, `Mapper`, `Enum`, `Exception`, `Config`, `Properties`, `CacheService`, `Client`/`Api` |
| `ReactiveRulesTest` | Prohíbe `Mono/Flux.block*`, `Thread.sleep`, `Future.get`/`CompletableFuture.join` fuera de `config` |
| `CycleRulesTest` | Sin ciclos entre los paquetes de primer nivel |
| `TestMirrorRulesTest` | Cada clase de producción tiene `<Clase>Test` en el mismo paquete (exentos: interfaces, records portadores de datos listados) |

Complementos: JaCoCo con umbral **LINE ≥ 90 % y BRANCH ≥ 90 %** (`jacocoTestCoverageVerification`,
enganchado a `check`) y `javac -Werror` (cualquier warning rompe el build).

## Consecuencias

**Positivas**
- Una violación rompe el build con un mensaje que indica la regla, el motivo y la línea.
- El evaluador puede comprobar la arquitectura ejecutando un comando
  (`./gradlew test --tests "com.ticketing.architecture.*"`).
- La regla de tests espejo empujó la cobertura real a ~100 % de líneas.

**Negativas**
- Algunas interpretaciones requieren excepciones documentadas (por ejemplo `@Qualifier` permitido
  en `business`, `org.reactivestreams` junto a Reactor); se listan en
  `docs/design/architecture-rules.md`.
- Las reglas de nombres no detectan malos diseños con buenos nombres: no sustituyen la revisión.

## Alternativas consideradas

- **Módulos Gradle por capa**: aislamiento por compilación, pero más configuración y no cubre
  sufijos ni tests espejo.
- **JPMS (`module-info`)**: fricción con Spring y con las librerías reflexivas.
- **Solo revisión de código**: no escala ni se puede demostrar.

## Referencias

- `docs/design/architecture-rules.md`, `build.gradle.kts` (JaCoCo, `-Werror`).
