package com.ticketing.architecture;

import static com.ticketing.architecture.ArchitectureFixtures.BUSINESS;
import static com.ticketing.architecture.ArchitectureFixtures.CACHE;
import static com.ticketing.architecture.ArchitectureFixtures.CLIENT;
import static com.ticketing.architecture.ArchitectureFixtures.DOMAIN;
import static com.ticketing.architecture.ArchitectureFixtures.PERSISTENCE;
import static com.ticketing.architecture.ArchitectureFixtures.PRESENTATION;
import static com.ticketing.architecture.ArchitectureFixtures.UTIL;
import static com.ticketing.architecture.ArchitectureFixtures.productionClasses;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.targetOwner;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameStartingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.properties.HasName;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Forbids blocking calls on the reactive request and worker paths (implementation spec section 1, item 4).
 * Only {@code config} may block, and only at startup (migration runner). Both direct calls and method
 * references (e.g. {@code Mono::block}) are detected.
 */
class ReactiveRulesTest {

    private static final String[] NON_BLOCKING_LAYERS =
            {PRESENTATION, BUSINESS, PERSISTENCE, CLIENT, CACHE, DOMAIN, UTIL};

    @Test
    void shouldNotBlockOnMonoOrFlux() {
        noClasses().that().resideInAnyPackage(NON_BLOCKING_LAYERS)
                .should().accessTargetWhere(invocationOf(Mono.class, nameStartingWith("block"))
                        .or(invocationOf(Flux.class, nameStartingWith("block")))
                        .as("the target is Mono.block*() or Flux.block*()"))
                .because("blocking a Reactor pipeline stalls an event-loop thread; compose the publisher instead")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void shouldNotSleep() {
        noClasses().that().resideInAnyPackage(NON_BLOCKING_LAYERS)
                .should().accessTargetWhere(invocationOf(Thread.class, name("sleep"))
                        .as("the target is Thread.sleep(..)"))
                .because("waiting must be expressed with Reactor operators (delayElement, Flux.interval, retry backoff)")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    @Test
    void shouldNotWaitForFutures() {
        noClasses().that().resideInAnyPackage(NON_BLOCKING_LAYERS)
                .should().accessTargetWhere(invocationOf(CompletableFuture.class, name("join"))
                        .or(invocationOf(Future.class, name("get")))
                        .as("the target is CompletableFuture.get/join(..) or Future.get(..)"))
                .because("AWS SDK futures must be adapted with Mono.fromFuture instead of being awaited")
                .allowEmptyShould(true)
                .check(productionClasses());
    }

    private static DescribedPredicate<JavaAccess<?>> invocationOf(
            Class<?> owner, DescribedPredicate<? super HasName> methodName) {
        return targetOwner(assignableTo(owner)).and(target(methodName));
    }
}
