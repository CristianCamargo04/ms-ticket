package com.ticketing.architecture;

import static com.ticketing.architecture.ArchitectureFixtures.CACHE_PORTS;
import static com.ticketing.architecture.ArchitectureFixtures.CACHE_ROOT;
import static com.ticketing.architecture.ArchitectureFixtures.CLIENT_DTO;
import static com.ticketing.architecture.ArchitectureFixtures.CLIENT_REST_API;
import static com.ticketing.architecture.ArchitectureFixtures.CLIENT_REST_APIS;
import static com.ticketing.architecture.ArchitectureFixtures.CLIENT_SDK_PORTS;
import static com.ticketing.architecture.ArchitectureFixtures.CLIENT_SDK_ROOT;
import static com.ticketing.architecture.ArchitectureFixtures.CONFIG;
import static com.ticketing.architecture.ArchitectureFixtures.CONTROLLER_CONTRACTS;
import static com.ticketing.architecture.ArchitectureFixtures.DOMAIN_EXCEPTION;
import static com.ticketing.architecture.ArchitectureFixtures.ENUMS;
import static com.ticketing.architecture.ArchitectureFixtures.IMPL;
import static com.ticketing.architecture.ArchitectureFixtures.MAPPER;
import static com.ticketing.architecture.ArchitectureFixtures.PERSISTENCE_DTO;
import static com.ticketing.architecture.ArchitectureFixtures.PERSISTENCE_ENTITY;
import static com.ticketing.architecture.ArchitectureFixtures.PERSISTENCE_PORTS;
import static com.ticketing.architecture.ArchitectureFixtures.PRESENTATION_DTO;
import static com.ticketing.architecture.ArchitectureFixtures.PRESENTATION_ROOT;
import static com.ticketing.architecture.ArchitectureFixtures.USE_CASE_TREE;
import static com.ticketing.architecture.ArchitectureFixtures.are;
import static com.ticketing.architecture.ArchitectureFixtures.interfacesIn;
import static com.ticketing.architecture.ArchitectureFixtures.productionClasses;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.INTERFACES;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.conditions.ArchConditions.haveSimpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enforces the class-name suffixes of implementation spec section 1 (item 3). Only top-level classes are
 * checked: nested types (e.g. the records of a sealed outcome or a properties group) may be named freely.
 */
class NamingConventionRulesTest {

    /** Use-case contracts anywhere in the use-case tree (root or family sub-packages), but never in impl. */
    private static final DescribedPredicate<JavaClass> USE_CASE_CONTRACTS = interfacesIn(USE_CASE_TREE)
            .and(resideOutsideOfPackage(IMPL))
            .as("use-case interfaces in '%s'", USE_CASE_TREE);

    private static final DescribedPredicate<JavaClass> CLIENT_CONTRACTS = CLIENT_SDK_PORTS
            .or(CLIENT_REST_APIS.and(INTERFACES))
            .as("client interfaces of package '%s' or in '%s'", CLIENT_SDK_ROOT, CLIENT_REST_API);

    @Test
    void useCaseInterfacesShouldBeSuffixedWithUseCase() {
        classes().that(are(USE_CASE_CONTRACTS)).and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("UseCase")
                .because("a use case is one business action named <Verb><Object>UseCase")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void useCaseImplementationsShouldBeSuffixedWithUseCaseImpl() {
        classes().that().areTopLevelClasses().and().implement(USE_CASE_CONTRACTS)
                .should().haveSimpleNameEndingWith("UseCaseImpl")
                .because("use-case implementations are named <Name>UseCaseImpl, 1:1 with their interface")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void persistenceInterfacesShouldBeSuffixedWithPersistence() {
        classes().that(are(PERSISTENCE_PORTS)).and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("Persistence")
                .because("persistence ports consumed by business are named <Name>Persistence")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void persistenceImplementationsShouldBeSuffixedWithPersistenceImpl() {
        classes().that().areTopLevelClasses().and().implement(PERSISTENCE_PORTS)
                .should().haveSimpleNameEndingWith("PersistenceImpl")
                .because("persistence implementations are named <Name>PersistenceImpl")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void entitiesShouldBeSuffixedWithEntity() {
        classes().that().resideInAPackage(PERSISTENCE_ENTITY).and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("Entity")
                .because("persistence entities are named <Name>Entity")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void controllerInterfacesShouldBeSuffixedWithController() {
        classes().that(are(CONTROLLER_CONTRACTS)).and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("Controller")
                .because("endpoint contracts in '" + PRESENTATION_ROOT + "' are named <Feature><Channel>Controller")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void controllerImplementationsShouldBeSuffixedWithControllerImpl() {
        classes().that().areTopLevelClasses().and().implement(CONTROLLER_CONTRACTS)
                .should().haveSimpleNameEndingWith("ControllerImpl")
                .because("controller implementations are named <Feature><Channel>ControllerImpl")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void presentationDtosShouldBeSuffixedWithRequestDtoOrResponseDto() {
        classes().that().resideInAPackage(PRESENTATION_DTO).and().areTopLevelClasses()
                .should(haveSimpleNameEndingWith("RequestDto").or(haveSimpleNameEndingWith("ResponseDto")))
                .because("presentation DTOs are named <Name>RequestDto or <Name>ResponseDto")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void clientAndPersistenceDtosShouldBeSuffixedWithDto() {
        classes().that().resideInAnyPackage(CLIENT_DTO, PERSISTENCE_DTO).and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("Dto")
                .because("client wire objects and persistence projections are named <Name>Dto")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void mappersShouldBeSuffixedWithMapper() {
        classes().that().resideInAPackage(MAPPER).and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("Mapper")
                .because("mapper packages only hold <Name>Mapper classes")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void enumsShouldBeSuffixedWithEnum() {
        classes().that().resideInAPackage(ENUMS).and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("Enum")
                .because("shared enums are named <Name>Enum")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void domainExceptionsShouldBeSuffixedWithException() {
        classes().that().resideInAPackage(DOMAIN_EXCEPTION).and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("Exception")
                .because("business exceptions are named <Name>Exception")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void configurationClassesShouldBeSuffixedWithConfigAndResideInConfig() {
        classes().that().areAnnotatedWith(Configuration.class)
                .should().haveSimpleNameEndingWith("Config")
                .andShould().resideInAPackage(CONFIG)
                .because("Spring configuration classes are named <Topic>Config and live in the composition root")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void configurationPropertiesShouldBeSuffixedWithPropertiesAndResideInConfig() {
        classes().that().areAnnotatedWith(ConfigurationProperties.class)
                .should().haveSimpleNameEndingWith("Properties")
                .andShould().resideInAPackage(CONFIG)
                .because("bound configuration properties are named <Name>Properties and live in config")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void cacheInterfacesShouldBeSuffixedWithCacheService() {
        classes().that(are(CACHE_PORTS)).and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("CacheService")
                .because("cache contracts in '" + CACHE_ROOT + "' are named <Name>CacheService")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void cacheImplementationsShouldBeSuffixedWithCacheServiceImpl() {
        classes().that().areTopLevelClasses().and().implement(CACHE_PORTS)
                .should().haveSimpleNameEndingWith("CacheServiceImpl")
                .because("cache implementations are named <Name>CacheServiceImpl")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void sdkClientInterfacesShouldBeSuffixedWithClient() {
        classes().that(are(CLIENT_SDK_PORTS)).and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("Client")
                .because("SDK client contracts are named <Provider>Client")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void restApiInterfacesShouldBeSuffixedWithApi() {
        classes().that().resideInAPackage(CLIENT_REST_API).and().areInterfaces().and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("Api")
                .because("REST client contracts are named <System><Resource>Api")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void clientImplementationsShouldBeSuffixedWithClientImpl() {
        classes().that().areTopLevelClasses().and().implement(CLIENT_CONTRACTS)
                .should().haveSimpleNameEndingWith("ClientImpl")
                .because("client implementations are named <Provider>ClientImpl / <System><Resource>ClientImpl")
                .allowEmptyShould(true)
                .check(productionClasses());
    }
}
