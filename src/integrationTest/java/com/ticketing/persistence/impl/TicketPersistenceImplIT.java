package com.ticketing.persistence.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.domain.data.TicketData;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.persistence.support.PersistenceTestContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class TicketPersistenceImplIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int CAPACITY = 130;

    private static PersistenceTestContext context;
    private static String eventId;
    private static Instant createdAt;

    @BeforeAll
    static void createTickets() {
        context = PersistenceTestContext.create();
        eventId = UUID.randomUUID().toString();
        createdAt = PersistenceTestContext.now();
        Long written = context.tickets().saveAll(Flux.range(1, CAPACITY)
                        .map(sequence -> TicketData.available(eventId, TicketData.ticketIdFor(sequence), createdAt)))
                .block(TIMEOUT);
        assertThat(written).isEqualTo(CAPACITY);
    }

    @Test
    void shouldWriteNothingForAnEmptyStream() {
        StepVerifier.create(context.tickets().saveAll(Flux.empty())).expectNext(0L).verifyComplete();
    }

    @Test
    void shouldReadTicketsByIdInRequestOrderSkippingMissingOnes() {
        List<TicketData> tickets = context.tickets()
                .findByIds(eventId, List.of("T-000120", "T-000002", "T-999999", "T-000002"))
                .collectList().block(TIMEOUT);

        assertThat(tickets).containsExactly(
                TicketData.available(eventId, "T-000120", createdAt),
                TicketData.available(eventId, "T-000002", createdAt));
    }

    @Test
    void shouldReadMoreThanOneBatchOfIds() {
        List<String> ids = IntStream.rangeClosed(1, CAPACITY).mapToObj(TicketData::ticketIdFor).toList();

        assertThat(context.tickets().findByIds(eventId, ids).collectList().block(TIMEOUT))
                .extracting(TicketData::ticketId).containsExactlyElementsOf(ids);
    }

    @Test
    void shouldScanAvailableTicketsFromAGivenTicket() {
        assertThat(context.tickets().findAvailable(eventId, "", 3).collectList().block(TIMEOUT))
                .containsExactly(
                        TicketData.available(eventId, "T-000001", createdAt),
                        TicketData.available(eventId, "T-000002", createdAt),
                        TicketData.available(eventId, "T-000003", createdAt));
        assertThat(context.tickets().findAvailable(eventId, "T-000125", 10).collectList().block(TIMEOUT))
                .extracting(TicketData::ticketId)
                .containsExactly("T-000125", "T-000126", "T-000127", "T-000128", "T-000129", "T-000130");
        assertThat(context.tickets().findAvailable(eventId, "", 0).collectList().block(TIMEOUT)).isEmpty();
        assertThat(context.tickets().findAvailable(UUID.randomUUID().toString(), "", 5).collectList()
                .block(TIMEOUT)).isEmpty();
    }

    @Test
    void shouldExcludeTicketsThatAreNoLongerAvailable() {
        String otherEvent = UUID.randomUUID().toString();
        Instant now = PersistenceTestContext.now();
        context.tickets().saveAll(Flux.just(
                TicketData.available(otherEvent, "T-000001", now),
                new TicketData(otherEvent, "T-000002", TicketStatusEnum.SOLD, "order-1", null, 3, now),
                TicketData.available(otherEvent, "T-000003", now))).block(TIMEOUT);

        assertThat(context.tickets().findAvailable(otherEvent, "", 10).collectList().block(TIMEOUT))
                .extracting(TicketData::ticketId).containsExactly("T-000001", "T-000003");
        assertThat(context.tickets().findByIds(otherEvent, List.of("T-000002")).blockFirst(TIMEOUT))
                .isEqualTo(new TicketData(otherEvent, "T-000002", TicketStatusEnum.SOLD, "order-1", null, 3, now));
    }
}
