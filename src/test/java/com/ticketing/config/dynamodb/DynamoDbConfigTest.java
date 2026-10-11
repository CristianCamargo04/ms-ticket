package com.ticketing.config.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ticketing.config.properties.AwsProperties;
import com.ticketing.domain.event.EventPolicy;
import com.ticketing.persistence.DistributedLockPersistence;
import com.ticketing.persistence.EventPersistence;
import com.ticketing.persistence.InventoryTransactionPersistence;
import com.ticketing.persistence.OrderPersistence;
import com.ticketing.persistence.TicketPersistence;
import com.ticketing.persistence.TicketTransitionPersistence;
import com.ticketing.persistence.impl.support.DynamoDbTableNames;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

class DynamoDbConfigTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AwsProperties.class)
    static class PropertiesConfig {
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.ticketing.persistence")
    static class PersistenceScan {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, DynamoDbConfig.class)
            .withBean(DynamoDbAsyncClient.class, () -> mock(DynamoDbAsyncClient.class));

    @Test
    void shouldExposeTheEnhancedClientAndDefaultTableNames() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DynamoDbEnhancedAsyncClient.class);
            assertThat(context.getBean(DynamoDbTableNames.class)).isEqualTo(new DynamoDbTableNames(
                    "ticketing-events", "ticketing-tickets", "ticketing-orders", "ticketing-ticket-transitions",
                    "ticketing-distributed-locks", 8));
        });
    }

    @Test
    void shouldWireEveryPersistenceAdapter() {
        contextRunner
                .withUserConfiguration(PersistenceScan.class)
                .withBean(EventPolicy.class, () -> new EventPolicy(20_000, Duration.ofSeconds(1), 4))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EventPersistence.class);
                    assertThat(context).hasSingleBean(TicketPersistence.class);
                    assertThat(context).hasSingleBean(OrderPersistence.class);
                    assertThat(context).hasSingleBean(InventoryTransactionPersistence.class);
                    assertThat(context).hasSingleBean(TicketTransitionPersistence.class);
                    assertThat(context).hasSingleBean(DistributedLockPersistence.class);
                });
    }

    @Test
    void shouldHonourConfiguredTableNamesAndShards() {
        contextRunner
                .withPropertyValues(
                        "aws.dynamodb.tables.events=prod-events",
                        "aws.dynamodb.tables.ticket-transitions=prod-transitions",
                        "aws.dynamodb.deadline-shards=16")
                .run(context -> {
                    DynamoDbTableNames names = context.getBean(DynamoDbTableNames.class);
                    assertThat(names.events()).isEqualTo("prod-events");
                    assertThat(names.ticketTransitions()).isEqualTo("prod-transitions");
                    assertThat(names.tickets()).isEqualTo("ticketing-tickets");
                    assertThat(names.deadlineShards()).isEqualTo(16);
                });
    }
}
