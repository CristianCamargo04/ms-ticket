package com.ticketing.config.time;

import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public RandomGenerator randomGenerator() {
        return () -> ThreadLocalRandom.current().nextLong();
    }
}
