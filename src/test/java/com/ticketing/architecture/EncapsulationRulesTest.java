package com.ticketing.architecture;

import static com.ticketing.architecture.ArchitectureFixtures.CLIENT;
import static com.ticketing.architecture.ArchitectureFixtures.CLIENT_DTO;
import static com.ticketing.architecture.ArchitectureFixtures.CONFIG;
import static com.ticketing.architecture.ArchitectureFixtures.PERSISTENCE;
import static com.ticketing.architecture.ArchitectureFixtures.PERSISTENCE_DTO;
import static com.ticketing.architecture.ArchitectureFixtures.PERSISTENCE_ENTITY;
import static com.ticketing.architecture.ArchitectureFixtures.PRESENTATION;
import static com.ticketing.architecture.ArchitectureFixtures.PRESENTATION_DTO;
import static com.ticketing.architecture.ArchitectureFixtures.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import org.junit.jupiter.api.Test;

/**
 * Keeps layer-private data structures (entities and DTOs) inside the layer that owns them
 * (implementation spec section 1 and docs/design/architecture-rules.md).
 */
class EncapsulationRulesTest {

    @Test
    void persistenceEntitiesShouldOnlyBeUsedByPersistenceAndConfig() {
        classes().that().resideInAPackage(PERSISTENCE_ENTITY)
                .should().onlyHaveDependentClassesThat().resideInAnyPackage(PERSISTENCE, CONFIG)
                .because("persistence exposes domain data upwards; entities never leave the layer"
                        + " (config may only register their table schemas)")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void persistenceDtosShouldOnlyBeUsedByPersistence() {
        classes().that().resideInAPackage(PERSISTENCE_DTO)
                .should().onlyHaveDependentClassesThat().resideInAPackage(PERSISTENCE)
                .because("query projections are an internal detail of persistence")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void clientDtosShouldOnlyBeUsedByClient() {
        classes().that().resideInAPackage(CLIENT_DTO)
                .should().onlyHaveDependentClassesThat().resideInAPackage(CLIENT)
                .because("client translates domain requests to wire DTOs with a *ClientMapper; DTOs never leave client")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void presentationDtosShouldOnlyBeUsedByPresentation() {
        classes().that().resideInAPackage(PRESENTATION_DTO)
                .should().onlyHaveDependentClassesThat().resideInAPackage(PRESENTATION)
                .because("request/response DTOs are mapped to domain objects inside presentation")
                .allowEmptyShould(true)
                .check(productionClasses());
    }
}
