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

/**
 * Turns {@link TicketingProperties} into the domain policy records consumed by the business layer.
 *
 * <p>There are two {@link RetryPolicy} beans, {@code inventoryRetryPolicy} and {@code publishRetryPolicy}. Business
 * classes may not import Spring's {@code @Qualifier}, so they select one by naming their constructor parameter
 * exactly like the bean (the build compiles with {@code -parameters}).
 */
@Configuration(proxyBeanMethods = false)
public class DomainPolicyConfig {

    /** Host name used when the local host name cannot be resolved. */
    static final String UNKNOWN_HOST = "unknown-host";

    /**
     * Order rules.
     *
     * @param properties application settings
     * @return the order policy
     */
    @Bean
    public OrderPolicy orderPolicy(TicketingProperties properties) {
        TicketingProperties.Orders orders = properties.orders();
        return new OrderPolicy(orders.holdTtl(), orders.confirmationTimeout(), orders.republishAfter(),
                orders.maxTicketsPerOrder(), orders.maxComplimentaryPerRequest(), orders.candidateFactor());
    }

    /**
     * Event rules.
     *
     * @param properties application settings
     * @return the event policy
     */
    @Bean
    public EventPolicy eventPolicy(TicketingProperties properties) {
        TicketingProperties.Events events = properties.events();
        return new EventPolicy(events.maxCapacity(), events.availabilityStreamInterval(),
                events.ticketWriteConcurrency());
    }

    /**
     * Sweeper settings; the lease owner id is the host name plus a random suffix, unique per instance.
     *
     * @param properties      application settings
     * @param randomGenerator random source for the instance suffix
     * @return the sweeper policy
     */
    @Bean
    public SweeperPolicy sweeperPolicy(TicketingProperties properties, RandomGenerator randomGenerator) {
        TicketingProperties.Sweeper sweeper = properties.sweeper();
        String hostName = hostName(() -> InetAddress.getLocalHost().getHostName());
        return new SweeperPolicy(sweeper.lease(), sweeper.batchSize(), sweeper.concurrency(),
                instanceId(hostName, randomGenerator));
    }

    /**
     * Retries of inventory transaction conflicts.
     *
     * @param properties application settings
     * @return the inventory retry policy
     */
    @Bean
    public RetryPolicy inventoryRetryPolicy(TicketingProperties properties) {
        TicketingProperties.Retry.InventoryRetry retry = properties.retry().inventory();
        return new RetryPolicy(retry.maxAttempts(), retry.minBackoff(), retry.maxBackoff(), retry.jitter());
    }

    /**
     * Retries of queue publications.
     *
     * @param properties application settings
     * @return the publish retry policy
     */
    @Bean
    public RetryPolicy publishRetryPolicy(TicketingProperties properties) {
        TicketingProperties.Retry.PublishRetry retry = properties.retry().publish();
        return new RetryPolicy(retry.maxAttempts(), retry.minBackoff(), retry.maxBackoff(), retry.jitter());
    }

    /**
     * Builds a unique instance id.
     *
     * @param hostName        host name
     * @param randomGenerator random source
     * @return {@code <hostName>-<8 hex digits>}
     */
    static String instanceId(String hostName, RandomGenerator randomGenerator) {
        return hostName + "-" + HexFormat.of().toHexDigits(randomGenerator.nextInt());
    }

    /**
     * Resolves the host name, falling back to {@link #UNKNOWN_HOST} when it is unavailable or blank.
     *
     * @param resolver host name lookup (may block: only called once at startup)
     * @return the host name
     */
    static String hostName(Callable<String> resolver) {
        try {
            String name = resolver.call();
            return name == null || name.isBlank() ? UNKNOWN_HOST : name;
        } catch (Exception _) {
            return UNKNOWN_HOST;
        }
    }
}
