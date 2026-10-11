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

public record PersistenceTestContext(DynamoDbAsyncClient client, DynamoDbTableNames tableNames,
                                     EventPersistenceImpl events, TicketPersistenceImpl tickets,
                                     OrderPersistenceImpl orders, InventoryTransactionPersistenceImpl inventory,
                                     TicketTransitionPersistenceImpl transitions,
                                     DistributedLockPersistenceImpl locks) {
    public static final int DEADLINE_SHARDS = 4;

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

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

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

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

    public OrderData queuedOrder(String eventId, OrderTypeEnum type, int quantity) {
        Instant now = now();
        OrderData order = OrderData.newQueued(UUID.randomUUID().toString(), eventId, "customer-1", type, quantity,
                "request-hash", now.plusSeconds(30), now);
        orders.insertIfAbsent(order).block(TIMEOUT);
        return orders.findById(order.orderId()).block(TIMEOUT);
    }

    public EventData event(String eventId) {
        return events.findById(eventId).block(TIMEOUT);
    }

    public OrderData order(String orderId) {
        return orders.findById(orderId).block(TIMEOUT);
    }
}
