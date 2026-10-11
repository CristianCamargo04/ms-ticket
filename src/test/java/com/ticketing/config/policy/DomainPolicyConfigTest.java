package com.ticketing.config.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.config.properties.TicketingProperties;
import com.ticketing.config.time.ClockConfig;
import com.ticketing.domain.event.EventPolicy;
import com.ticketing.domain.inventory.RetryPolicy;
import com.ticketing.domain.inventory.SweeperPolicy;
import com.ticketing.domain.order.OrderPolicy;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class DomainPolicyConfigTest {

    /** {@code nextInt()} of this generator is {@code 0x12345678}. */
    private static final RandomGenerator FIXED_RANDOM = () -> 0x1234_5678_9abc_def0L;

    private final DomainPolicyConfig config = new DomainPolicyConfig();
    private final TicketingProperties defaults = new Binder().bindOrCreate("ticketing", TicketingProperties.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TicketingProperties.class)
    static class PropertiesConfig {
    }

    /** Consumer resolving one of the two retry policies by parameter name, as the business layer does. */
    record RetryPolicyConsumer(RetryPolicy inventoryRetryPolicy, RetryPolicy publishRetryPolicy) {
    }

    @Test
    void shouldBuildOrderPolicyFromProperties() {
        OrderPolicy policy = config.orderPolicy(defaults);

        assertThat(policy).isEqualTo(new OrderPolicy(Duration.ofMinutes(10), Duration.ofMinutes(5),
                Duration.ofSeconds(30), 10, 40, 3));
    }

    @Test
    void shouldBuildEventPolicyFromProperties() {
        EventPolicy policy = config.eventPolicy(defaults);

        assertThat(policy).isEqualTo(new EventPolicy(20000, Duration.ofSeconds(1), 4));
    }

    @Test
    void shouldBuildRetryPoliciesFromProperties() {
        assertThat(config.inventoryRetryPolicy(defaults))
                .isEqualTo(new RetryPolicy(6, Duration.ofMillis(25), Duration.ofSeconds(1), 0.5));
        assertThat(config.publishRetryPolicy(defaults))
                .isEqualTo(new RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(1), 0.5));
    }

    @Test
    void shouldBuildSweeperPolicyWithUniqueInstanceId() {
        SweeperPolicy policy = config.sweeperPolicy(defaults, FIXED_RANDOM);

        assertThat(policy.lease()).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.batchSize()).isEqualTo(100);
        assertThat(policy.concurrency()).isEqualTo(8);
        assertThat(policy.instanceId()).endsWith("-12345678").hasSizeGreaterThan("-12345678".length());
    }

    @Test
    void shouldReflectCustomSettings() {
        TicketingProperties custom = new Binder(new MapConfigurationPropertySource(Map.of(
                "ticketing.orders.max-tickets-per-order", "4",
                "ticketing.events.max-capacity", "500",
                "ticketing.sweeper.batch-size", "25",
                "ticketing.retry.publish.max-attempts", "1")))
                .bindOrCreate("ticketing", TicketingProperties.class);

        assertThat(config.orderPolicy(custom).maxTicketsPerOrder()).isEqualTo(4);
        assertThat(config.eventPolicy(custom).maxCapacity()).isEqualTo(500);
        assertThat(config.sweeperPolicy(custom, FIXED_RANDOM).batchSize()).isEqualTo(25);
        assertThat(config.publishRetryPolicy(custom).maxAttempts()).isEqualTo(1);
    }

    @Test
    void shouldBuildInstanceIdFromHostNameAndRandomSuffix() {
        assertThat(DomainPolicyConfig.instanceId("worker-a", FIXED_RANDOM)).isEqualTo("worker-a-12345678");
    }

    @Test
    void shouldResolveHostName() {
        assertThat(DomainPolicyConfig.hostName(() -> "ip-10-0-0-1")).isEqualTo("ip-10-0-0-1");
    }

    @Test
    void shouldFallBackWhenHostNameIsUnavailable() {
        assertThat(DomainPolicyConfig.hostName(() -> null)).isEqualTo(DomainPolicyConfig.UNKNOWN_HOST);
        assertThat(DomainPolicyConfig.hostName(() -> " ")).isEqualTo(DomainPolicyConfig.UNKNOWN_HOST);
        assertThat(DomainPolicyConfig.hostName(() -> {
            throw new UnknownHostException("no dns");
        })).isEqualTo(DomainPolicyConfig.UNKNOWN_HOST);
    }

    @Test
    void shouldExposeEveryPolicyBeanAndBothRetryPoliciesByName() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class, ClockConfig.class, DomainPolicyConfig.class)
                .withBean(RetryPolicyConsumer.class)
                .withPropertyValues("ticketing.retry.inventory.max-attempts=7")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OrderPolicy.class);
                    assertThat(context).hasSingleBean(EventPolicy.class);
                    assertThat(context).hasSingleBean(SweeperPolicy.class);
                    assertThat(context.getBeansOfType(RetryPolicy.class))
                            .containsOnlyKeys("inventoryRetryPolicy", "publishRetryPolicy");
                    RetryPolicyConsumer consumer = context.getBean(RetryPolicyConsumer.class);
                    assertThat(consumer.inventoryRetryPolicy().maxAttempts()).isEqualTo(7);
                    assertThat(consumer.publishRetryPolicy().maxAttempts()).isEqualTo(3);
                });
    }
}
