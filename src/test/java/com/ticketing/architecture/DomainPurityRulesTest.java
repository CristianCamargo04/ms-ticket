package com.ticketing.architecture;

import static com.ticketing.architecture.ArchitectureFixtures.DOMAIN;
import static com.ticketing.architecture.ArchitectureFixtures.ENUMS;
import static com.ticketing.architecture.ArchitectureFixtures.JAVA;
import static com.ticketing.architecture.ArchitectureFixtures.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import org.junit.jupiter.api.Test;

class DomainPurityRulesTest {
    @Test
    void domainShouldOnlyDependOnJavaDomainAndEnums() {
        classes().that().resideInAPackage(DOMAIN)
                .should().onlyDependOnClassesThat().resideInAnyPackage(JAVA, DOMAIN, ENUMS)
                .because("the domain model is pure Java (no Spring, Reactor, AWS SDK or Jackson)")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void enumsShouldOnlyDependOnJava() {
        classes().that().resideInAPackage(ENUMS)
                .should().onlyDependOnClassesThat().resideInAnyPackage(JAVA, ENUMS)
                .because("shared enums depend on nothing but the JDK (same-package references are inherent to enums)")
                .allowEmptyShould(true)
                .check(productionClasses());
    }
}
