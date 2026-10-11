package com.ticketing.persistence.support;

import com.ticketing.config.properties.AwsProperties;
import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.data.TicketData;
import com.ticketing.domain.event.EventPolicy;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.persistence.impl.DistributedLockPersistenceImpl;
import com.ticketing.persistence.impl.EventPersistenceImpl;
import com.ticketing.persistence.impl.InventoryTransactionPersistenceImpl;
import com.ticketing.persistence.impl.OrderPersistenceImpl;
import com.ticketing.persistence.impl.TicketPersistenceImpl;
import com.ticketing.persistence.impl.TicketTransitionPersistenceImpl;
import com.ticketing.persistence.impl.mapper.EventEntityMapper;
import com.ticketing.persistence.impl.mapper.OrderEntityMapper;
import com.ticketing.persistence.impl.mapper.TicketEntityMapper;
import com.ticketing.persistence.impl.mapper.TicketTransitionEntityMapper;
import com.ticketing.persistence.impl.support.DeadlinePartitioner;
import com.ticketing.persistence.impl.support.DynamoDbTableNames;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Every persistence adapter wired against a fresh set of DynamoDB Local tables created from the real migrations,
 * plus helpers to seed events, tickets and orders.
 *
 * @param client       low-level client
 * @param tableNames   physical table names
 * @param events       events adapter
 * @param tickets      tickets adapter
 * @param orders       orders adapter
 * @param inventory    inventory transactions adapter
 * @param transitions  audit adapter
 * @param locks        distributed lock adapter
 */
public record PersistenceTestContext(DynamoDbAsyncClient client, DynamoDbTableNames tableNames,
                                     EventPersistenceImpl events, TicketPersistenceImpl tickets,
                                     OrderPersistenceImpl orders, InventoryTransactionPersistenceImpl inventory,
                                     TicketTransitionPersistenceImpl transitions,
                                     DistributedLockPersistenceImpl locks) {

    /** Number of deadline shards used by the tests. */
    public static final int DEADLINE_SHARDS = 4;

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /**
     * Creates new tables through the migration runner and wires the adapters.
     *
     * @return the context
     */
    public static PersistenceTestContext create() {
        AwsProperties.Tables tables = DynamoDbLocalContainer.uniqueTables();
        DynamoDbLocalContainer.migrate(tables);
        DynamoDbAsyncClient client = DynamoDbLocalContainer.client();
        DynamoDbEnhancedAsyncClient enhanced = DynamoDbEnhancedAsyncClient.builder().dynamoDbClient(client).build();
        DynamoDbTableNames names = new DynamoDbTableNames(tables.events(), tables.tickets(), tables.orders(),
                tables.ticketTransitions(), tables.distributedLocks(), DEADLINE_SHARDS);
        DeadlinePartitioner partitioner = new DeadlinePartitioner(names);
        TicketTransitionEntityMapper transitionMapper = new TicketTransitionEntityMapper();
        return new PersistenceTestContext(client, names,
                new EventPersistenceImpl(client, enhanced, names, new EventEntityMapper()),
                new TicketPersistenceImpl(client, names, new TicketEntityMapper(),
                        new EventPolicy(20_000, Duration.ofSeconds(1), 4)),
                new OrderPersistenceImpl(client, enhanced, names, new OrderEntityMapper(partitioner), partitioner),
                new InventoryTransactionPersistenceImpl(client, names, partitioner, transitionMapper),
                new TicketTransitionPersistenceImpl(client, names, transitionMapper),
                new DistributedLockPersistenceImpl(client, names));
    }

    /**
     * The current instant truncated to milliseconds (the storage precision), so that the expected values a test
     * derives from it ({@code now.plus(HOLD)}, ...) compare equal to the stored ones. The adapters do not rely on it:
     * they return values with the storage precision themselves (see
     * {@code InventoryTransactionPersistenceImplIT.shouldReturnTheStoredStateForSubMillisecondCommandInstants}).
     *
     * @return now
     */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * Creates an ON_SALE event with {@code capacity} available tickets.
     *
     * @param capacity number of tickets
     * @return the persisted event
     */
    public EventData onSaleEventWithTickets(int capacity) {
        Instant now = now();
        EventData event = EventData.newPublishing(UUID.randomUUID().toString(), "Concert", "Arena",
                now.plus(Duration.ofDays(30)), capacity, new BigDecimal("49.90"), "USD", now);
        events.create(event).block(TIMEOUT);
        tickets.saveAll(Flux.range(1, capacity)
                        .map(sequence -> TicketData.available(event.eventId(), TicketData.ticketIdFor(sequence), now)))
                .block(TIMEOUT);
        events.updateStatus(event.eventId(), EventStatusEnum.PUBLISHING, EventStatusEnum.ON_SALE, now).block(TIMEOUT);
        return events.findById(event.eventId()).block(TIMEOUT);
    }

    /**
     * Inserts a queued order and returns it as read back from the table.
     *
     * @param eventId  the event
     * @param type     the order type
     * @param quantity number of tickets
     * @return the persisted order
     */
    public OrderData queuedOrder(String eventId, OrderTypeEnum type, int quantity) {
        Instant now = now();
        OrderData order = OrderData.newQueued(UUID.randomUUID().toString(), eventId, "customer-1", type, quantity,
                "request-hash", now.plusSeconds(30), now);
        orders.insertIfAbsent(order).block(TIMEOUT);
        return orders.findById(order.orderId()).block(TIMEOUT);
    }

    /**
     * Reads an event.
     *
     * @param eventId the event
     * @return the event
     */
    public EventData event(String eventId) {
        return events.findById(eventId).block(TIMEOUT);
    }

    /**
     * Reads an order.
     *
     * @param orderId the order
     * @return the order
     */
    public OrderData order(String orderId) {
        return orders.findById(orderId).block(TIMEOUT);
    }
}
