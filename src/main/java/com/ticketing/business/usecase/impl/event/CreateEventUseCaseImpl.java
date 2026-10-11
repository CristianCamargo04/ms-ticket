package com.ticketing.business.usecase.impl.event;

import com.ticketing.business.usecase.CreateEventUseCase;
import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.TicketData;
import com.ticketing.domain.event.EventPolicy;
import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.domain.internal_request.CreateEventRequest;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.persistence.EventPersistence;
import com.ticketing.persistence.TicketPersistence;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Creates an event in {@code PUBLISHING}, pre-generates all of its tickets and puts it {@code ON_SALE}.
 *
 * <p>The tickets are written in {@link EventPolicy#ticketWriteConcurrency()} contiguous slices in parallel (each slice
 * is one {@link TicketPersistence#saveAll(Flux)} call, chunked into batch writes by the persistence layer). The event
 * only goes on sale once every ticket is persisted, so a failed generation leaves an event that never accepts orders
 * (it stays {@code PUBLISHING}).
 */
@Service
public class CreateEventUseCaseImpl implements CreateEventUseCase {

    /** Maximum length of the name and the venue. */
    static final int MAX_TEXT_LENGTH = 120;

    /** Maximum decimals of the ticket price. */
    static final int MAX_PRICE_SCALE = 2;

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateEventUseCaseImpl.class);
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private final EventPersistence eventPersistence;
    private final TicketPersistence ticketPersistence;
    private final EventPolicy eventPolicy;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param eventPersistence  events table
     * @param ticketPersistence tickets table
     * @param eventPolicy       event rules (maximum capacity, ticket write concurrency)
     * @param clock             time source
     */
    public CreateEventUseCaseImpl(EventPersistence eventPersistence, TicketPersistence ticketPersistence,
                                  EventPolicy eventPolicy, Clock clock) {
        this.eventPersistence = eventPersistence;
        this.ticketPersistence = ticketPersistence;
        this.eventPolicy = eventPolicy;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Mono<EventData> execute(CreateEventRequest request) {
        return Mono.defer(() -> {
            Instant now = clock.instant();
            validate(request, now);
            EventData event = EventData.newPublishing(UUID.randomUUID().toString(), request.name(), request.venue(),
                    request.eventDate(), request.totalCapacity(), request.ticketPrice(), request.currency(), now);
            return eventPersistence.create(event)
                    .flatMap(created -> generateTickets(created, now).then(putOnSale(created, now)));
        });
    }

    private Mono<Long> generateTickets(EventData event, Instant now) {
        int capacity = event.totalCapacity();
        int sliceSize = Math.ceilDiv(capacity, Math.min(eventPolicy.ticketWriteConcurrency(), capacity));
        int slices = Math.ceilDiv(capacity, sliceSize);
        return Flux.range(0, slices)
                .flatMap(slice -> {
                    int firstSequence = slice * sliceSize + 1;
                    int count = Math.min(sliceSize, capacity - firstSequence + 1);
                    return ticketPersistence.saveAll(Flux.range(firstSequence, count)
                            .map(sequence -> TicketData.available(event.eventId(), TicketData.ticketIdFor(sequence),
                                    now)));
                }, slices)
                .reduce(0L, Long::sum)
                .flatMap(written -> written == capacity
                        ? Mono.just(written)
                        : Mono.error(new IllegalStateException("Event " + event.eventId() + " expected " + capacity
                                + " generated tickets but " + written + " were written")));
    }

    private Mono<EventData> putOnSale(EventData event, Instant now) {
        return Mono.defer(() -> eventPersistence.updateStatus(event.eventId(), EventStatusEnum.PUBLISHING,
                        EventStatusEnum.ON_SALE, now))
                .flatMap(updated -> {
                    if (!updated) {
                        return Mono.error(new IllegalStateException(
                                "Event " + event.eventId() + " left PUBLISHING before going on sale"));
                    }
                    LOGGER.info("Event {} created with {} tickets and put on sale", event.eventId(),
                            event.totalCapacity());
                    return Mono.just(event.withStatus(EventStatusEnum.ON_SALE, now));
                });
    }

    private void validate(CreateEventRequest request, Instant now) {
        requireText(request.name(), "name");
        requireText(request.venue(), "venue");
        if (request.eventDate() == null || !request.eventDate().isAfter(now)) {
            throw new DomainValidationException("eventDate must be in the future");
        }
        if (request.totalCapacity() < 1 || request.totalCapacity() > eventPolicy.maxCapacity()) {
            throw new DomainValidationException("totalCapacity must be between 1 and " + eventPolicy.maxCapacity());
        }
        BigDecimal price = request.ticketPrice();
        if (price == null || price.signum() < 0 || price.stripTrailingZeros().scale() > MAX_PRICE_SCALE) {
            throw new DomainValidationException("ticketPrice must be >= 0 with at most " + MAX_PRICE_SCALE
                    + " decimals");
        }
        if (request.currency() == null || !CURRENCY.matcher(request.currency()).matches()) {
            throw new DomainValidationException("currency must be an ISO-4217 code of three upper-case letters");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT_LENGTH) {
            throw new DomainValidationException(field + " must be non-blank with at most " + MAX_TEXT_LENGTH
                    + " characters");
        }
    }
}
