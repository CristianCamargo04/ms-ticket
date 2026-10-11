package com.ticketing.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.INTERFACES;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;

import com.tngtech.archunit.ArchConfiguration;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Arrays;

final class ArchitectureFixtures {
    static final String BASE_PACKAGE = "com.ticketing";

    static final String APPLICATION = "com.ticketing..";

    static final String PRESENTATION = "com.ticketing.presentation..";
    static final String BUSINESS = "com.ticketing.business..";
    static final String DOMAIN = "com.ticketing.domain..";
    static final String PERSISTENCE = "com.ticketing.persistence..";
    static final String CLIENT = "com.ticketing.client..";
    static final String CACHE = "com.ticketing.cache..";
    static final String CONFIG = "com.ticketing.config..";
    static final String ENUMS = "com.ticketing.enums..";
    static final String UTIL = "com.ticketing.util..";

    static final String IMPL = "..impl..";

    static final String USE_CASE_ROOT = "com.ticketing.business.usecase";
    static final String USE_CASE_TREE = "com.ticketing.business.usecase..";
    static final String PERSISTENCE_ROOT = "com.ticketing.persistence";
    static final String CLIENT_SDK_ROOT = "com.ticketing.client.sdk";
    static final String CLIENT_REST_API = "com.ticketing.client.rest.api..";
    static final String CACHE_ROOT = "com.ticketing.cache";
    static final String PRESENTATION_ROOT = "com.ticketing.presentation";

    static final String PRESENTATION_DTO = "com.ticketing.presentation.dto..";
    static final String PERSISTENCE_ENTITY = "com.ticketing.persistence.entity..";
    static final String PERSISTENCE_DTO = "com.ticketing.persistence.dto..";
    static final String CLIENT_DTO = "com.ticketing.client.dto..";
    static final String DOMAIN_EXCEPTION = "com.ticketing.domain.exception..";
    static final String MAPPER = "com.ticketing..mapper..";

    static final String JAVA = "java..";
    static final String REACTOR = "reactor..";

    static final String REACTIVE_STREAMS = "org.reactivestreams..";
    static final String SPRING_STEREOTYPE = "org.springframework.stereotype..";
    static final String SLF4J = "org.slf4j..";
    static final String LOMBOK = "lombok..";

    static final DescribedPredicate<JavaClass> USE_CASE_PORTS =
            interfacesIn(USE_CASE_ROOT).as("use-case interfaces of package '%s'", USE_CASE_ROOT);

    static final DescribedPredicate<JavaClass> PERSISTENCE_PORTS =
            interfacesIn(PERSISTENCE_ROOT).as("persistence interfaces of package '%s'", PERSISTENCE_ROOT);

    static final DescribedPredicate<JavaClass> CLIENT_SDK_PORTS =
            interfacesIn(CLIENT_SDK_ROOT).as("SDK client interfaces of package '%s'", CLIENT_SDK_ROOT);

    static final DescribedPredicate<JavaClass> CLIENT_REST_APIS =
            resideInAPackage(CLIENT_REST_API).as("REST API contracts in '%s'", CLIENT_REST_API);

    static final DescribedPredicate<JavaClass> CACHE_PORTS =
            interfacesIn(CACHE_ROOT).as("cache interfaces of package '%s'", CACHE_ROOT);

    static final DescribedPredicate<JavaClass> CONTROLLER_CONTRACTS =
            interfacesIn(PRESENTATION_ROOT).as("controller interfaces of package '%s'", PRESENTATION_ROOT);

    static final DescribedPredicate<JavaClass> OUTBOUND_PORTS = PERSISTENCE_PORTS
            .or(CLIENT_SDK_PORTS)
            .or(CLIENT_REST_APIS)
            .or(CACHE_PORTS)
            .as("outbound ports (%s, %s, %s, %s)",
                    PERSISTENCE_PORTS.getDescription(),
                    CLIENT_SDK_PORTS.getDescription(),
                    CLIENT_REST_APIS.getDescription(),
                    CACHE_PORTS.getDescription());

    private ArchitectureFixtures() {
    }

    static JavaClasses productionClasses() {
        return ProductionClasses.INSTANCE;
    }

    static JavaClasses testClasses() {
        return TestClasses.INSTANCE;
    }

    static DescribedPredicate<JavaClass> are(DescribedPredicate<JavaClass> predicate) {
        return predicate.as("are %s", predicate.getDescription());
    }

    static DescribedPredicate<JavaClass> interfacesIn(String packageIdentifier) {
        return INTERFACES.and(notAnnotation()).and(resideInAPackage(packageIdentifier));
    }

    static DescribedPredicate<JavaClass> applicationClassesOutsideOf(String... packageIdentifiers) {
        DescribedPredicate<JavaClass> outside = DescribedPredicate.alwaysTrue();
        for (String packageIdentifier : packageIdentifiers) {
            outside = outside.and(resideOutsideOfPackage(packageIdentifier));
        }
        return resideInAPackage(APPLICATION)
                .and(outside)
                .as("reside in '%s' but outside of %s", APPLICATION, Arrays.toString(packageIdentifiers));
    }

    static DescribedPredicate<JavaClass> nonPortClassesOf(
            DescribedPredicate<? super JavaClass> ports, String... packageIdentifiers) {
        return resideInAnyPackage(packageIdentifiers)
                .and(DescribedPredicate.not(ports))
                .as("reside in %s but are not %s", Arrays.toString(packageIdentifiers), ports.getDescription());
    }

    static boolean residesInImplPackage(JavaClass javaClass) {
        return resideInAPackage(IMPL).test(javaClass);
    }

    private static DescribedPredicate<JavaClass> notAnnotation() {
        return DescribedPredicate.describe("not annotation", javaClass -> !javaClass.isAnnotation());
    }

    private static final class ProductionClasses {
        private static final JavaClasses INSTANCE = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_PACKAGE_INFOS)
                .importPackages(BASE_PACKAGE);
    }

    private static final class TestClasses {
        private static final JavaClasses INSTANCE = ArchConfiguration.withThreadLocalScope(configuration -> {
            configuration.setResolveMissingDependenciesFromClassPath(false);
            return new ClassFileImporter()
                    .withImportOption(new ImportOption.OnlyIncludeTests())
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_PACKAGE_INFOS)
                    .importPackages(BASE_PACKAGE);
        });
    }
}
