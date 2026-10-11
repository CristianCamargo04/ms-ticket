# Architecture rules (ArchUnit)

The layering, naming and testing rules of `implementation-spec.md` section 1 are executable: they live in
`src/test/java/com/ticketing/architecture/` (ArchUnit 1.5.1, plain JUnit Jupiter tests) and run with every
`./gradlew test`. This is the only test package that does not mirror production code.

```
presentation -> business.usecase (interfaces) -> persistence | client | cache (interfaces) -> domain
config = composition root (may depend on everything; nothing depends on config)
```

Run only this suite:

```
./gradlew test --tests "com.ticketing.architecture.*" --console=plain
```

## How the suite works

- `ArchitectureFixtures` imports the `com.ticketing` classes **once per JVM**: production classes with
  `ImportOption.DoNotIncludeTests` and test classes with `ImportOption.OnlyIncludeTests`; `package-info`
  classes are ignored. It also holds the package identifiers and the port predicates shared by all rules.
- Every rule uses `allowEmptyShould(true)`: while a layer does not exist yet its rules pass vacuously, and
  they start biting as soon as code lands. `TestMirrorRulesTest#importsShouldSeparateProductionFromTestClasses`
  guards against an import misconfiguration that would silently make every rule vacuous.
- A violation fails the build with ArchUnit's report: the rule, its `because` clause and one line per
  offending dependency/class with its source location.

## Rules

### LayerDependencyRulesTest — the dependency matrix

| Test | Rule | Rationale |
|---|---|---|
| `domainShouldNotDependOnAnyOtherApplicationPackage` | `domain..` uses no `com.ticketing` package other than `domain..` / `enums..` | The domain is the core; it knows no layer. |
| `enumsShouldNotDependOnAnyOtherApplicationPackage` | `enums..` uses no other `com.ticketing` package | Shared enums are leaves of the graph. |
| `utilShouldOnlyDependOnJavaAndReactor` | `util..` depends only on `java..`, `reactor..`, `org.reactivestreams..`, `util..` | Cross-cutting helpers must not couple to a layer or framework. |
| `businessShouldNotDependOnPresentationOrConfig` | `business..` never uses `presentation..` or `config..` | Business is called by presentation and wired by config, never the reverse. |
| `businessShouldAccessPersistenceClientAndCacheOnlyThroughTheirPortInterfaces` | From `persistence..`, `client..`, `cache..` business may only use interfaces of the root packages `persistence`, `client.sdk`, `cache` and anything in `client.rest.api..` | Business depends on ports, never on `impl`, `entity`, `dto` or `mapper` classes. |
| `businessShouldOnlyUseApprovedThirdPartyLibraries` | Outside `com.ticketing`, business depends only on `java..`, `reactor..`, `org.reactivestreams..`, `org.springframework.stereotype..`, `org.slf4j..` and `@Qualifier` | Use cases stay free of infrastructure (AWS SDK, Jackson, Spring Web, ...). |
| `presentationShouldNotDependOnPersistenceClientCacheOrConfig` | `presentation..` never uses `persistence..`, `client..`, `cache..`, `config..` | Controllers only talk to use cases. |
| `presentationShouldAccessBusinessOnlyThroughUseCaseInterfaces` | From `business..` presentation may only use interfaces of `com.ticketing.business.usecase` | Never `business.usecase.impl..`. |
| `persistenceShouldNotDependOnOtherLayers` | `persistence..` never uses `presentation..`, `business..`, `client..`, `cache..`, `config..` | Outbound adapters are independent of each other and of upstream layers. |
| `clientShouldNotDependOnOtherLayers` | `client..` never uses `presentation..`, `business..`, `persistence..`, `cache..`, `config..` | Same. |
| `cacheShouldNotDependOnOtherLayers` | `cache..` never uses `presentation..`, `business..`, `persistence..`, `client..`, `config..` | Same. |
| `nothingOutsideConfigShouldDependOnConfig` | Only `config..` may use `config..` | Config is the composition root; layers receive settings records they own (spec rule 5). |

### DomainPurityRulesTest — framework-free core

| Test | Rule | Rationale |
|---|---|---|
| `domainShouldOnlyDependOnJavaDomainAndEnums` | `domain..` depends only on `java..`, `domain..`, `enums..` | No Spring, Reactor, AWS SDK or Jackson in the model. |
| `enumsShouldOnlyDependOnJava` | `enums..` depends only on `java..` (and `enums..` itself) | Enums are plain JDK types. |

### EncapsulationRulesTest — layer-private data structures

| Test | Rule | Rationale |
|---|---|---|
| `persistenceEntitiesShouldOnlyBeUsedByPersistenceAndConfig` | `persistence.entity..` is used only by `persistence..` and `config..` | Persistence exposes `domain` data upwards; config may register table schemas. |
| `persistenceDtosShouldOnlyBeUsedByPersistence` | `persistence.dto..` is used only by `persistence..` | Query projections are internal. |
| `clientDtosShouldOnlyBeUsedByClient` | `client.dto..` is used only by `client..` | Wire DTOs are translated by a `*ClientMapper`. |
| `presentationDtosShouldOnlyBeUsedByPresentation` | `presentation.dto..` is used only by `presentation..` | DTOs are mapped to domain objects at the edge. |

### ImplPlacementRulesTest — contracts and implementations

| Test | Rule | Rationale |
|---|---|---|
| `interfacesShouldNotResideInImplPackages` | No interface (annotation types excepted) lives in `..impl..` | Contracts live in the parent package (spec rule 1). |
| `classesNamedImplShouldResideInImplPackages` | Every `*Impl` lives in `..impl..` | Spec rule 2. |
| `classesNamedImplShouldImplementAnApplicationInterfaceOfANonImplPackage` | Every `*Impl` implements at least one interface of a non-impl `com.ticketing` package | An `Impl` exists to implement a contract (spec rule 2). |
| `persistenceInterfacesShouldHaveAnImplementation` | Each interface of `com.ticketing.persistence` has a `<Name>Impl` in an `impl` package | Every port is backed (spec rule 1). |
| `useCaseInterfacesShouldHaveAnImplementation` | Each interface of `com.ticketing.business.usecase` has a `<Name>Impl` in an `impl` package | Use cases are implemented 1:1. |

### NamingConventionRulesTest — suffixes (spec section 1, item 3)

Only top-level classes are checked; nested types (sealed-outcome records, properties groups) are free.

| Test | Rule |
|---|---|
| `useCaseInterfacesShouldBeSuffixedWithUseCase` | Interfaces in `business.usecase..` (outside impl) end with `UseCase` |
| `useCaseImplementationsShouldBeSuffixedWithUseCaseImpl` | Implementations of use-case interfaces end with `UseCaseImpl` |
| `persistenceInterfacesShouldBeSuffixedWithPersistence` | Interfaces of the `persistence` root end with `Persistence` |
| `persistenceImplementationsShouldBeSuffixedWithPersistenceImpl` | Their implementations end with `PersistenceImpl` |
| `entitiesShouldBeSuffixedWithEntity` | Classes in `persistence.entity..` end with `Entity` |
| `controllerInterfacesShouldBeSuffixedWithController` | Interfaces of the `presentation` root end with `Controller` |
| `controllerImplementationsShouldBeSuffixedWithControllerImpl` | Their implementations end with `ControllerImpl` |
| `presentationDtosShouldBeSuffixedWithRequestDtoOrResponseDto` | Classes in `presentation.dto..` end with `RequestDto` or `ResponseDto` |
| `clientAndPersistenceDtosShouldBeSuffixedWithDto` | Classes in `client.dto..` and `persistence.dto..` end with `Dto` |
| `mappersShouldBeSuffixedWithMapper` | Classes in any `..mapper..` package end with `Mapper` (put helpers in `support`, not `mapper`) |
| `enumsShouldBeSuffixedWithEnum` | Classes in `enums..` end with `Enum` |
| `domainExceptionsShouldBeSuffixedWithException` | Classes in `domain.exception..` end with `Exception` |
| `configurationClassesShouldBeSuffixedWithConfigAndResideInConfig` | `@Configuration` classes end with `Config` and live in `config..` |
| `configurationPropertiesShouldBeSuffixedWithPropertiesAndResideInConfig` | `@ConfigurationProperties` classes end with `Properties` and live in `config..` |
| `cacheInterfacesShouldBeSuffixedWithCacheService` | Interfaces of the `cache` root end with `CacheService` |
| `cacheImplementationsShouldBeSuffixedWithCacheServiceImpl` | Their implementations end with `CacheServiceImpl` |
| `sdkClientInterfacesShouldBeSuffixedWithClient` | Interfaces of the `client.sdk` root end with `Client` |
| `restApiInterfacesShouldBeSuffixedWithApi` | Interfaces in `client.rest.api..` end with `Api` |
| `clientImplementationsShouldBeSuffixedWithClientImpl` | Implementations of SDK clients / REST APIs end with `ClientImpl` (`XxxApi` -> `XxxClientImpl`) |

### ReactiveRulesTest — no blocking on reactive paths

Applies to `presentation`, `business`, `persistence`, `client`, `cache`, `domain` and `util`. `config` may
block only at startup (migration runner). Direct calls **and** method references (`Mono::block`) are caught.

| Test | Forbidden |
|---|---|
| `shouldNotBlockOnMonoOrFlux` | `Mono.block*()`, `Flux.block*()` (any subtype) |
| `shouldNotSleep` | `Thread.sleep(..)` |
| `shouldNotWaitForFutures` | `CompletableFuture.get/join(..)`, `Future.get(..)` |

### CycleRulesTest

`topLevelPackagesShouldBeFreeOfCycles`: the slices `com.ticketing.(*)..` form a directed acyclic graph.

### TestMirrorRulesTest — every class has its test (spec section 1, item 6)

`everyProductionClassShouldHaveAMirroredTest`: every top-level production class needs
`<package>.<SimpleName>Test` among the test classes; the failure lists every missing test, e.g.
`Missing test <com.ticketing.util.HashUtilTest> for production class <com.ticketing.util.HashUtil>`.

Exempt: interfaces (including annotation types), `TicketingApplication`, nested types (covered by the test of
their top-level class) and the pure data carriers of `presentation.dto..`, `client.dto..`, `persistence.dto..`,
`persistence.entity..`, `domain.internal_request..`, `domain.external_request..`, `domain.exception..`
(covered by `TicketingExceptionTest` + `GlobalErrorHandlerTest`) and `config.properties..`.

## Interpretations of the spec

- **Reactive Streams**: wherever the spec allows `reactor..`, `org.reactivestreams..` is allowed too, since
  Reactor's API is typed with `Publisher` (`Flux.from(Publisher)`, `Retry#generateCompanion`, ...).
- **`@Qualifier` in business**: the spec registers the `RetryPolicy` beans under the qualifiers
  `inventoryRetryPolicy` / `publishRetryPolicy`, so business may use
  `org.springframework.beans.factory.annotation.Qualifier` (and nothing else from that package).
- **Third-party allow-lists** are enforced where the spec lists exact packages (`domain`, `enums`, `util`,
  `business`). For `persistence`, `client`, `cache` and `presentation` the spec names library families
  ("AWS SDK", "Spring Web/Security", ...) and omits obvious needs (stereotypes, logging), so only their
  `com.ticketing` dependencies are enforced.
- **Same-package references in `enums`** are allowed (constant bodies and switch maps compile to nested
  classes; one enum may reference another).
- **`cache -> util`** is neither listed as allowed nor as forbidden; it is not flagged.

## Changing a rule

Rules mirror `implementation-spec.md` section 1. Change the spec first, then the rule and this document in the
same commit. Prefer a narrow, documented exemption (a package identifier or a single class) over weakening a
rule, and never add `@Disabled` to an architecture test.
