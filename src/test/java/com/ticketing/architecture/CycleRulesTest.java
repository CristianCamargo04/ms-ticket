package com.ticketing.architecture;

import static com.ticketing.architecture.ArchitectureFixtures.productionClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import org.junit.jupiter.api.Test;

/**
 * Forbids dependency cycles between the top-level application packages ({@code presentation}, {@code business},
 * {@code domain}, {@code persistence}, {@code client}, {@code cache}, {@code config}, {@code enums}, {@code util}).
 */
class CycleRulesTest {

    @Test
    void topLevelPackagesShouldBeFreeOfCycles() {
        slices().matching("com.ticketing.(*)..")
                .should().beFreeOfCycles()
                .because("the layered architecture is a directed acyclic graph")
                .allowEmptyShould(true)
                .check(productionClasses());
    }
}
