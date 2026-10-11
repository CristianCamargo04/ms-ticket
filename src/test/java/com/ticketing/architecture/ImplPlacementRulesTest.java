package com.ticketing.architecture;

import static com.ticketing.architecture.ArchitectureFixtures.APPLICATION;
import static com.ticketing.architecture.ArchitectureFixtures.IMPL;
import static com.ticketing.architecture.ArchitectureFixtures.PERSISTENCE_PORTS;
import static com.ticketing.architecture.ArchitectureFixtures.USE_CASE_PORTS;
import static com.ticketing.architecture.ArchitectureFixtures.are;
import static com.ticketing.architecture.ArchitectureFixtures.productionClasses;
import static com.ticketing.architecture.ArchitectureFixtures.residesInImplPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImplPlacementRulesTest {
    @Test
    void interfacesShouldNotResideInImplPackages() {
        noClasses().that().areInterfaces().and().areNotAnnotations()
                .should().resideInAPackage(IMPL)
                .because("every interface lives in the parent package; impl packages only hold implementations")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void classesNamedImplShouldResideInImplPackages() {
        classes().that().haveSimpleNameEndingWith("Impl")
                .should().resideInAPackage(IMPL)
                .because("implementations live in an impl sub-package of the package that declares their contract")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void classesNamedImplShouldImplementAnApplicationInterfaceOfANonImplPackage() {
        classes().that().haveSimpleNameEndingWith("Impl")
                .should(implementAnApplicationInterfaceOfANonImplPackage())
                .because("an *Impl class exists to implement a contract declared in a parent (non-impl) package")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void persistenceInterfacesShouldHaveAnImplementation() {
        classes().that(are(PERSISTENCE_PORTS))
                .should(haveAnImplementationNamedAfterThemInAnImplPackage())
                .because("every persistence port is backed by persistence.impl.<Name>Impl")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void useCaseInterfacesShouldHaveAnImplementation() {
        classes().that(are(USE_CASE_PORTS))
                .should(haveAnImplementationNamedAfterThemInAnImplPackage())
                .because("every use case is implemented 1:1 by business.usecase.impl.<family>.<Name>Impl")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    private static ArchCondition<JavaClass> implementAnApplicationInterfaceOfANonImplPackage() {
        return new ArchCondition<>("implement at least one interface of a non-impl package of '%s'", APPLICATION) {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                List<String> contracts = javaClass.getAllRawInterfaces().stream()
                        .filter(resideInAPackage(APPLICATION))
                        .filter(contract -> !residesInImplPackage(contract))
                        .map(JavaClass::getName)
                        .sorted()
                        .toList();
                if (contracts.isEmpty()) {
                    events.add(SimpleConditionEvent.violated(javaClass, String.format(
                            "Class <%s> does not implement any interface of a non-impl application package"
                                    + " (it implements %s) in %s",
                            javaClass.getName(), interfaceNames(javaClass), javaClass.getSourceCodeLocation())));
                } else {
                    events.add(SimpleConditionEvent.satisfied(javaClass, String.format(
                            "Class <%s> implements %s", javaClass.getName(), contracts)));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> haveAnImplementationNamedAfterThemInAnImplPackage() {
        return new ArchCondition<>("have an implementation named <SimpleName>Impl in an impl package") {
            @Override
            public void check(JavaClass contract, ConditionEvents events) {
                String expectedName = contract.getSimpleName() + "Impl";
                List<String> implementations = contract.getAllSubclasses().stream()
                        .filter(candidate -> !candidate.isInterface())
                        .filter(candidate -> candidate.getSimpleName().equals(expectedName))
                        .filter(ArchitectureFixtures::residesInImplPackage)
                        .map(JavaClass::getName)
                        .sorted()
                        .toList();
                if (implementations.isEmpty()) {
                    events.add(SimpleConditionEvent.violated(contract, String.format(
                            "Interface <%s> has no implementation named %s in an impl package in %s",
                            contract.getName(), expectedName, contract.getSourceCodeLocation())));
                } else {
                    events.add(SimpleConditionEvent.satisfied(contract, String.format(
                            "Interface <%s> is implemented by %s", contract.getName(), implementations)));
                }
            }
        };
    }

    private static List<String> interfaceNames(JavaClass javaClass) {
        return javaClass.getAllRawInterfaces().stream().map(JavaClass::getName).sorted().toList();
    }
}
