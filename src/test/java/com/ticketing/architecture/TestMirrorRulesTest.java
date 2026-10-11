package com.ticketing.architecture;

import static com.ticketing.architecture.ArchitectureFixtures.productionClasses;
import static com.ticketing.architecture.ArchitectureFixtures.testClasses;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.INTERFACES;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.TOP_LEVEL_CLASSES;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static java.util.stream.Collectors.toUnmodifiableSet;
import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.TicketingApplication;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TestMirrorRulesTest {
    private static final String[] EXEMPT_DATA_CARRIER_PACKAGES = {
        "com.ticketing.presentation.dto..",
        "com.ticketing.client.dto..",
        "com.ticketing.persistence.dto..",
        "com.ticketing.persistence.entity..",
        "com.ticketing.domain.internal_request..",
        "com.ticketing.domain.external_request..",
        "com.ticketing.domain.exception..",
        "com.ticketing.config.properties..",
    };

    private static final DescribedPredicate<JavaClass> CLASSES_REQUIRING_A_TEST = TOP_LEVEL_CLASSES
            .and(not(INTERFACES))
            .and(not(equivalentTo(TicketingApplication.class)))
            .and(resideOutsideOfPackages(EXEMPT_DATA_CARRIER_PACKAGES))
            .as("are top-level, are neither interfaces nor %s and do not reside in the data-carrier packages [%s]",
                    TicketingApplication.class.getSimpleName(), String.join(", ", EXEMPT_DATA_CARRIER_PACKAGES));

    @Test
    void everyProductionClassShouldHaveAMirroredTest() {
        Set<String> testClassNames = testClasses().stream().map(JavaClass::getName).collect(toUnmodifiableSet());

        classes().that(CLASSES_REQUIRING_A_TEST)
                .should(haveATestNamedAfterThemInTheSamePackage(testClassNames))
                .because("every production class is covered by <SimpleName>Test in the same package of src/test/java")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void importsShouldSeparateProductionFromTestClasses() {
        assertThat(productionClasses().contain(TicketingApplication.class))
                .as("production import must contain the application class")
                .isTrue();
        assertThat(productionClasses().contain(TestMirrorRulesTest.class))
                .as("production import must not contain test classes")
                .isFalse();
        assertThat(testClasses().contain(TestMirrorRulesTest.class))
                .as("test import must contain the architecture tests")
                .isTrue();
        assertThat(testClasses().contain(TicketingApplication.class))
                .as("test import must not contain production classes")
                .isFalse();
    }

    private static ArchCondition<JavaClass> haveATestNamedAfterThemInTheSamePackage(Set<String> testClassNames) {
        return new ArchCondition<>("have a test class named <SimpleName>Test in the same package") {
            @Override
            public void check(JavaClass productionClass, ConditionEvents events) {
                String expectedTest = productionClass.getName() + "Test";
                if (testClassNames.contains(expectedTest)) {
                    events.add(SimpleConditionEvent.satisfied(productionClass, String.format(
                            "Class <%s> is tested by <%s>", productionClass.getName(), expectedTest)));
                } else {
                    events.add(SimpleConditionEvent.violated(productionClass, String.format(
                            "Missing test <%s> for production class <%s> in %s",
                            expectedTest, productionClass.getName(), productionClass.getSourceCodeLocation())));
                }
            }
        };
    }
}
