package com.ticketing.architecture;

import static com.ticketing.architecture.ArchitectureFixtures.APPLICATION;
import static com.ticketing.architecture.ArchitectureFixtures.BUSINESS;
import static com.ticketing.architecture.ArchitectureFixtures.CACHE;
import static com.ticketing.architecture.ArchitectureFixtures.CLIENT;
import static com.ticketing.architecture.ArchitectureFixtures.CONFIG;
import static com.ticketing.architecture.ArchitectureFixtures.DOMAIN;
import static com.ticketing.architecture.ArchitectureFixtures.ENUMS;
import static com.ticketing.architecture.ArchitectureFixtures.JAVA;
import static com.ticketing.architecture.ArchitectureFixtures.OUTBOUND_PORTS;
import static com.ticketing.architecture.ArchitectureFixtures.PERSISTENCE;
import static com.ticketing.architecture.ArchitectureFixtures.PRESENTATION;
import static com.ticketing.architecture.ArchitectureFixtures.REACTIVE_STREAMS;
import static com.ticketing.architecture.ArchitectureFixtures.REACTOR;
import static com.ticketing.architecture.ArchitectureFixtures.SLF4J;
import static com.ticketing.architecture.ArchitectureFixtures.SPRING_STEREOTYPE;
import static com.ticketing.architecture.ArchitectureFixtures.USE_CASE_PORTS;
import static com.ticketing.architecture.ArchitectureFixtures.UTIL;
import static com.ticketing.architecture.ArchitectureFixtures.applicationClassesOutsideOf;
import static com.ticketing.architecture.ArchitectureFixtures.nonPortClassesOf;
import static com.ticketing.architecture.ArchitectureFixtures.productionClasses;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * Enforces the dependency matrix of implementation spec section 1:
 * {@code presentation -> business.usecase (interfaces) -> persistence | client | cache (interfaces) -> domain},
 * with {@code config} as the composition root that nothing else may depend on.
 */
class LayerDependencyRulesTest {

    @Test
    void domainShouldNotDependOnAnyOtherApplicationPackage() {
        noClasses().that().resideInAPackage(DOMAIN)
                .should().dependOnClassesThat(applicationClassesOutsideOf(DOMAIN, ENUMS))
                .because("domain is the core of the architecture and may only use domain and enums")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void enumsShouldNotDependOnAnyOtherApplicationPackage() {
        noClasses().that().resideInAPackage(ENUMS)
                .should().dependOnClassesThat(applicationClassesOutsideOf(ENUMS))
                .because("shared enums are leaves of the dependency graph and may depend on nothing")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void utilShouldOnlyDependOnJavaAndReactor() {
        classes().that().resideInAPackage(UTIL)
                .should().onlyDependOnClassesThat().resideInAnyPackage(JAVA, REACTOR, REACTIVE_STREAMS, UTIL)
                .because("cross-cutting utilities may only use the JDK and Reactor, never another layer")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void businessShouldNotDependOnPresentationOrConfig() {
        noClasses().that().resideInAPackage(BUSINESS)
                .should().dependOnClassesThat().resideInAnyPackage(PRESENTATION, CONFIG)
                .because("business is called by presentation and wired by config, never the other way round")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void businessShouldAccessPersistenceClientAndCacheOnlyThroughTheirPortInterfaces() {
        noClasses().that().resideInAPackage(BUSINESS)
                .should().dependOnClassesThat(nonPortClassesOf(OUTBOUND_PORTS, PERSISTENCE, CLIENT, CACHE))
                .because("business depends on port interfaces only, never on impl, entity, dto or mapper classes"
                        + " of persistence, client or cache")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void businessShouldOnlyUseApprovedThirdPartyLibraries() {
        classes().that().resideInAPackage(BUSINESS)
                .should().onlyDependOnClassesThat(
                        resideInAnyPackage(APPLICATION, JAVA, REACTOR, REACTIVE_STREAMS, SPRING_STEREOTYPE, SLF4J)
                                .or(type(Qualifier.class))
                                .as("reside in the application, the JDK, Reactor, Spring stereotypes or SLF4J"
                                        + " (or are @Qualifier)"))
                .because("use cases must stay free of infrastructure frameworks (AWS SDK, Jackson, Spring Web, ...)")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void presentationShouldNotDependOnPersistenceClientCacheOrConfig() {
        noClasses().that().resideInAPackage(PRESENTATION)
                .should().dependOnClassesThat().resideInAnyPackage(PERSISTENCE, CLIENT, CACHE, CONFIG)
                .because("presentation only talks to business use cases")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void presentationShouldAccessBusinessOnlyThroughUseCaseInterfaces() {
        noClasses().that().resideInAPackage(PRESENTATION)
                .should().dependOnClassesThat(nonPortClassesOf(USE_CASE_PORTS, BUSINESS))
                .because("controllers depend on use-case interfaces, never on business.usecase.impl")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void persistenceShouldNotDependOnOtherLayers() {
        noClasses().that().resideInAPackage(PERSISTENCE)
                .should().dependOnClassesThat().resideInAnyPackage(PRESENTATION, BUSINESS, CLIENT, CACHE, CONFIG)
                .because("persistence is an outbound adapter that only knows domain, enums and util")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void clientShouldNotDependOnOtherLayers() {
        noClasses().that().resideInAPackage(CLIENT)
                .should().dependOnClassesThat().resideInAnyPackage(PRESENTATION, BUSINESS, PERSISTENCE, CACHE, CONFIG)
                .because("client is an outbound adapter that only knows domain, enums and util")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void cacheShouldNotDependOnOtherLayers() {
        noClasses().that().resideInAPackage(CACHE)
                .should().dependOnClassesThat().resideInAnyPackage(PRESENTATION, BUSINESS, PERSISTENCE, CLIENT, CONFIG)
                .because("cache is an outbound adapter that only knows domain and enums")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void nothingOutsideConfigShouldDependOnConfig() {
        noClasses().that().resideOutsideOfPackage(CONFIG)
                .should().dependOnClassesThat().resideInAPackage(CONFIG)
                .because("config is the composition root: layers receive their settings as records they own")
                .allowEmptyShould(true)
                .check(productionClasses());
    }
}
