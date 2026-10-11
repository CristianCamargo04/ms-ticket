package com.ticketing.config.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ClockConfigTest {

    private final ClockConfig config = new ClockConfig();

    @Test
    void shouldUseTheUtcSystemClock() {
        Clock clock = config.clock();

        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(clock).isEqualTo(Clock.systemUTC());
    }

    @Test
    void shouldGenerateBoundedRandomValues() {
        RandomGenerator random = config.randomGenerator();

        List<Integer> values = IntStream.range(0, 1_000).map(_ -> random.nextInt(10)).boxed().toList();

        assertThat(values).allSatisfy(value -> assertThat(value).isBetween(0, 9));
        assertThat(Set.copyOf(values)).hasSizeGreaterThan(1);
    }

    @Test
    void shouldBeSafeToShareAcrossThreads() {
        RandomGenerator random = config.randomGenerator();
        Set<Long> values = ConcurrentHashMap.newKeySet();

        IntStream.range(0, 10_000).parallel().forEach(_ -> values.add(random.nextLong()));

        assertThat(values).hasSizeGreaterThan(9_990);
    }
}
