package com.ticketing.config.time;

import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time and randomness sources, injected so that business code is deterministic under test.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /**
     * The application clock.
     *
     * @return the UTC system clock
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Thread-safe random source shared by concurrent reactive pipelines. {@code RandomGenerator.getDefault()} is not
     * thread-safe, so every call delegates to the calling thread's {@link ThreadLocalRandom} (no contention, no
     * shared mutable state).
     *
     * @return the random generator
     */
    @Bean
    public RandomGenerator randomGenerator() {
        return () -> ThreadLocalRandom.current().nextLong();
    }
}
