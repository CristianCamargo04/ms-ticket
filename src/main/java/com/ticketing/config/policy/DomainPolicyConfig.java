package com.ticketing.config.policy;

import com.ticketing.config.properties.TicketingProperties;
import com.ticketing.domain.event.EventPolicy;
import com.ticketing.domain.inventory.RetryPolicy;
import com.ticketing.domain.inventory.SweeperPolicy;
import com.ticketing.domain.order.OrderPolicy;
import java.net.InetAddress;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DomainPolicyConfig {
    static final String UNKNOWN_HOST = "unknown-host";

    @Bean
    public OrderPolicy orderPolicy(TicketingProperties properties) {
        TicketingProperties.Orders orders = properties.orders();
        return new OrderPolicy(orders.holdTtl(), orders.confirmationTimeout(), orders.republishAfter(),
                orders.maxTicketsPerOrder(), orders.maxComplimentaryPerRequest(), orders.candidateFactor());
    }

    @Bean
    public EventPolicy eventPolicy(TicketingProperties properties) {
        TicketingProperties.Events events = properties.events();
        return new EventPolicy(events.maxCapacity(), events.availabilityStreamInterval(),
                events.ticketWriteConcurrency());
    }

    @Bean
    public SweeperPolicy sweeperPolicy(TicketingProperties properties, RandomGenerator randomGenerator) {
        TicketingProperties.Sweeper sweeper = properties.sweeper();
        String hostName = hostName(() -> InetAddress.getLocalHost().getHostName());
        return new SweeperPolicy(sweeper.lease(), sweeper.batchSize(), sweeper.concurrency(),
                instanceId(hostName, randomGenerator));
    }

    @Bean
    public RetryPolicy inventoryRetryPolicy(TicketingProperties properties) {
        TicketingProperties.Retry.InventoryRetry retry = properties.retry().inventory();
        return new RetryPolicy(retry.maxAttempts(), retry.minBackoff(), retry.maxBackoff(), retry.jitter());
    }

    @Bean
    public RetryPolicy publishRetryPolicy(TicketingProperties properties) {
        TicketingProperties.Retry.PublishRetry retry = properties.retry().publish();
        return new RetryPolicy(retry.maxAttempts(), retry.minBackoff(), retry.maxBackoff(), retry.jitter());
    }

    static String instanceId(String hostName, RandomGenerator randomGenerator) {
        return hostName + "-" + HexFormat.of().toHexDigits(randomGenerator.nextInt());
    }

    static String hostName(Callable<String> resolver) {
        try {
            String name = resolver.call();
            return name == null || name.isBlank() ? UNKNOWN_HOST : name;
        } catch (Exception _) {
            return UNKNOWN_HOST;
        }
    }
}
