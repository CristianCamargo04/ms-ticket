package com.ticketing.persistence.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.PageData;
import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.persistence.support.PersistenceTestContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

class EventPersistenceImplIT {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static PersistenceTestContext context;

    @BeforeAll
    static void createTables() {
        context = PersistenceTestContext.create();
    }

    @Test
    void shouldCreateAndReadAnEvent() {
        EventData event = newEvent(PersistenceTestContext.now().plus(Duration.ofDays(10)));

        StepVerifier.create(context.events().create(event)).expectNext(event).verifyComplete();
        StepVerifier.create(context.events().findById(event.eventId())).expectNext(event).verifyComplete();
    }

    @Test
    void shouldRejectADuplicateEvent() {
        EventData event = newEvent(PersistenceTestContext.now().plus(Duration.ofDays(10)));
        context.events().create(event).block(TIMEOUT);

        StepVerifier.create(context.events().create(event))
                .expectError(ConditionalCheckFailedException.class)
                .verify(TIMEOUT);
    }

    @Test
    void shouldReturnEmptyForAnUnknownEvent() {
        StepVerifier.create(context.events().findById(UUID.randomUUID().toString())).verifyComplete();
    }

    @Test
    void shouldUpdateTheStatusOnlyFromTheExpectedStatus() {
        EventData event = newEvent(PersistenceTestContext.now().plus(Duration.ofDays(10)));
        context.events().create(event).block(TIMEOUT);
        Instant now = PersistenceTestContext.now().plusSeconds(1);

        StepVerifier.create(context.events().updateStatus(event.eventId(), EventStatusEnum.PUBLISHING,
                EventStatusEnum.ON_SALE, now)).expectNext(true).verifyComplete();
        StepVerifier.create(context.events().updateStatus(event.eventId(), EventStatusEnum.PUBLISHING,
                EventStatusEnum.CLOSED, now)).expectNext(false).verifyComplete();
        StepVerifier.create(context.events().updateStatus(UUID.randomUUID().toString(), EventStatusEnum.PUBLISHING,
                EventStatusEnum.ON_SALE, now)).expectNext(false).verifyComplete();

        assertThat(context.event(event.eventId())).isEqualTo(event.withStatus(EventStatusEnum.ON_SALE, now));
    }

    @Test
    void shouldPageThroughTheEventsOfAStatusInDateOrder() {
        Instant base = PersistenceTestContext.now().plus(Duration.ofDays(100));
        List<EventData> closed = new ArrayList<>();
        for (int day = 5; day >= 1; day--) {
            EventData event = newEvent(base.plus(Duration.ofDays(day)));
            context.events().create(event).block(TIMEOUT);
            context.events().updateStatus(event.eventId(), EventStatusEnum.PUBLISHING, EventStatusEnum.CLOSED,
                    base).block(TIMEOUT);
            closed.add(context.event(event.eventId()));
        }

        List<EventData> listed = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = null;
        do {
            PageData<EventData> page = context.events().findByStatus(EventStatusEnum.CLOSED, 2, cursor).block(TIMEOUT);
            listed.addAll(page.items());
            pageSizes.add(page.items().size());
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(pageSizes).startsWith(2, 2, 1);
        assertThat(listed).extracting(EventData::eventDate).isSorted();
        assertThat(listed).containsExactlyInAnyOrderElementsOf(closed);
    }

    @Test
    void shouldRejectMalformedAndForeignCursors() {
        EventData event = newEvent(PersistenceTestContext.now().plus(Duration.ofDays(1)));
        context.events().create(event).block(TIMEOUT);
        context.events().create(newEvent(PersistenceTestContext.now().plus(Duration.ofDays(2)))).block(TIMEOUT);
        String publishingCursor = context.events().findByStatus(EventStatusEnum.PUBLISHING, 1, null)
                .block(TIMEOUT).nextCursor();
        assertThat(publishingCursor).isNotNull();

        StepVerifier.create(context.events().findByStatus(EventStatusEnum.PUBLISHING, 1, "not-a-cursor!"))
                .expectError(DomainValidationException.class)
                .verify(TIMEOUT);
        StepVerifier.create(context.events().findByStatus(EventStatusEnum.ON_SALE, 1, publishingCursor))
                .expectError(DomainValidationException.class)
                .verify(TIMEOUT);
        for (String number : List.of("1e999999999", "1e60", "1.5")) {
            String tampered = Base64.getUrlEncoder().withoutPadding().encodeToString(("eventDate=N:" + number
                    + "&eventId=S:" + event.eventId() + "&status=S:PUBLISHING").getBytes(StandardCharsets.UTF_8));
            StepVerifier.create(context.events().findByStatus(EventStatusEnum.PUBLISHING, 1, tampered))
                    .expectError(DomainValidationException.class)
                    .verify(TIMEOUT);
        }
    }

    private static EventData newEvent(Instant eventDate) {
        Instant now = PersistenceTestContext.now();
        return EventData.newPublishing(UUID.randomUUID().toString(), "Festival", "Stadium", eventDate, 100,
                new BigDecimal("120.50"), "EUR", now);
    }
}
