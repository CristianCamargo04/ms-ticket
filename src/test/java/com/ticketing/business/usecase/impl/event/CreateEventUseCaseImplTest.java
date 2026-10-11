package com.ticketing.business.usecase.impl.event;

import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CLOCK;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.TicketData;
import com.ticketing.domain.event.EventPolicy;
import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.domain.internal_request.CreateEventRequest;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.persistence.EventPersistence;
import com.ticketing.persistence.TicketPersistence;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class CreateEventUseCaseImplTest {

    private static final Instant EVENT_DATE = NOW.plus(Duration.ofDays(30));
    private static final EventPolicy POLICY = new EventPolicy(1_000, Duration.ofSeconds(1), 4);

    @Mock
    private EventPersistence eventPersistence;

    @Mock
    private TicketPersistence ticketPersistence;

    private final Queue<TicketData> writtenTickets = new ConcurrentLinkedQueue<>();

    private CreateEventUseCaseImpl useCase() {
        return new CreateEventUseCaseImpl(eventPersistence, ticketPersistence, POLICY, CLOCK);
    }

    private static CreateEventRequest request(int capacity) {
        return new CreateEventRequest("Concert", "Arena", EVENT_DATE, capacity, new BigDecimal("25.50"), "USD");
    }

    private void persistEverything() {
        when(eventPersistence.create(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(ticketPersistence.saveAll(any())).thenAnswer(invocation -> invocation.<Flux<TicketData>>getArgument(0)
                .doOnNext(writtenTickets::add)
                .count());
    }

    @ParameterizedTest(name = "capacity {0} with 4 writers -> {1} slices")
    @CsvSource({"10, 4", "9, 3", "2, 2", "1, 1", "1000, 4"})
    void shouldCreateTheEventGenerateEveryTicketAndPutItOnSale(int capacity, int expectedSlices) {
        persistEverything();
        when(eventPersistence.updateStatus(anyString(), any(), any(), any())).thenReturn(Mono.just(true));

        StepVerifier.create(useCase().execute(request(capacity)))
                .assertNext(event -> {
                    assertThat(UUID.fromString(event.eventId())).isNotNull();
                    assertThat(event.status()).isEqualTo(EventStatusEnum.ON_SALE);
                    assertThat(event.totalCapacity()).isEqualTo(capacity);
                    assertThat(event.availableCount()).isEqualTo(capacity);
                    assertThat(event.name()).isEqualTo("Concert");
                    assertThat(event.venue()).isEqualTo("Arena");
                    assertThat(event.eventDate()).isEqualTo(EVENT_DATE);
                    assertThat(event.ticketPrice()).isEqualByComparingTo("25.50");
                    assertThat(event.currency()).isEqualTo("USD");
                    assertThat(event.version()).isEqualTo(EventData.INITIAL_VERSION + 1);
                    assertThat(event.createdAt()).isEqualTo(NOW);
                    verify(eventPersistence).updateStatus(event.eventId(), EventStatusEnum.PUBLISHING,
                            EventStatusEnum.ON_SALE, NOW);
                })
                .verifyComplete();

        ArgumentCaptor<EventData> created = ArgumentCaptor.forClass(EventData.class);
        verify(eventPersistence).create(created.capture());
        assertThat(created.getValue().status()).isEqualTo(EventStatusEnum.PUBLISHING);
        verify(ticketPersistence, times(expectedSlices)).saveAll(any());
        assertThat(writtenTickets).hasSize(capacity)
                .allSatisfy(ticket -> {
                    assertThat(ticket.eventId()).isEqualTo(created.getValue().eventId());
                    assertThat(ticket.status()).isEqualTo(TicketStatusEnum.AVAILABLE);
                    assertThat(ticket.orderId()).isNull();
                    assertThat(ticket.updatedAt()).isEqualTo(NOW);
                });
        assertThat(writtenTickets.stream().map(TicketData::ticketId).sorted().toList())
                .containsExactlyElementsOf(Stream.iterate(1, i -> i + 1).limit(capacity)
                        .map(TicketData::ticketIdFor).toList());
    }

    @Test
    void shouldAcceptPricesWithTrailingZeros() {
        persistEverything();
        when(eventPersistence.updateStatus(anyString(), any(), any(), any())).thenReturn(Mono.just(true));
        CreateEventRequest request = new CreateEventRequest("Concert", "Arena", EVENT_DATE, 1,
                new BigDecimal("10.500"), "EUR");

        StepVerifier.create(useCase().execute(request))
                .assertNext(event -> assertThat(event.ticketPrice()).isEqualByComparingTo("10.5"))
                .verifyComplete();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void shouldRejectInvalidRequestsWithoutTouchingPersistence(String rule, CreateEventRequest request) {
        StepVerifier.create(useCase().execute(request))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(DomainValidationException.class)
                        .hasMessageContaining(rule))
                .verify();

        verifyNoInteractions(eventPersistence, ticketPersistence);
    }

    static Stream<Arguments> invalidRequests() {
        BigDecimal price = new BigDecimal("10.00");
        String tooLong = "x".repeat(CreateEventUseCaseImpl.MAX_TEXT_LENGTH + 1);
        return Stream.of(
                Arguments.of("name", new CreateEventRequest(null, "Arena", EVENT_DATE, 10, price, "USD")),
                Arguments.of("name", new CreateEventRequest("  ", "Arena", EVENT_DATE, 10, price, "USD")),
                Arguments.of("name", new CreateEventRequest(tooLong, "Arena", EVENT_DATE, 10, price, "USD")),
                Arguments.of("venue", new CreateEventRequest("Concert", null, EVENT_DATE, 10, price, "USD")),
                Arguments.of("venue", new CreateEventRequest("Concert", "", EVENT_DATE, 10, price, "USD")),
                Arguments.of("venue", new CreateEventRequest("Concert", tooLong, EVENT_DATE, 10, price, "USD")),
                Arguments.of("eventDate", new CreateEventRequest("Concert", "Arena", null, 10, price, "USD")),
                Arguments.of("eventDate", new CreateEventRequest("Concert", "Arena", NOW, 10, price, "USD")),
                Arguments.of("eventDate",
                        new CreateEventRequest("Concert", "Arena", NOW.minusSeconds(1), 10, price, "USD")),
                Arguments.of("totalCapacity", new CreateEventRequest("Concert", "Arena", EVENT_DATE, 0, price, "USD")),
                Arguments.of("totalCapacity",
                        new CreateEventRequest("Concert", "Arena", EVENT_DATE, 1_001, price, "USD")),
                Arguments.of("ticketPrice", new CreateEventRequest("Concert", "Arena", EVENT_DATE, 10, null, "USD")),
                Arguments.of("ticketPrice", new CreateEventRequest("Concert", "Arena", EVENT_DATE, 10,
                        new BigDecimal("-0.01"), "USD")),
                Arguments.of("ticketPrice", new CreateEventRequest("Concert", "Arena", EVENT_DATE, 10,
                        new BigDecimal("1.001"), "USD")),
                Arguments.of("currency", new CreateEventRequest("Concert", "Arena", EVENT_DATE, 10, price, null)),
                Arguments.of("currency", new CreateEventRequest("Concert", "Arena", EVENT_DATE, 10, price, "usd")),
                Arguments.of("currency", new CreateEventRequest("Concert", "Arena", EVENT_DATE, 10, price, "EURO")));
    }

    @Test
    void shouldAcceptTheBoundaries() {
        persistEverything();
        when(eventPersistence.updateStatus(anyString(), any(), any(), any())).thenReturn(Mono.just(true));
        String maxText = "x".repeat(CreateEventUseCaseImpl.MAX_TEXT_LENGTH);
        CreateEventRequest request = new CreateEventRequest(maxText, maxText, NOW.plusMillis(1),
                POLICY.maxCapacity(), BigDecimal.ZERO, "USD");

        StepVerifier.create(useCase().execute(request))
                .assertNext(event -> assertThat(event.totalCapacity()).isEqualTo(POLICY.maxCapacity()))
                .verifyComplete();
    }

    @Test
    void shouldNotPutTheEventOnSaleWhenTicketsAreMissing() {
        when(eventPersistence.create(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(ticketPersistence.saveAll(any())).thenAnswer(invocation -> invocation.<Flux<TicketData>>getArgument(0)
                .count()
                .map(count -> count - 1));

        StepVerifier.create(useCase().execute(request(8)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("expected 8 generated tickets but 4 were written"))
                .verify();

        verify(eventPersistence, never()).updateStatus(anyString(), any(), any(), any());
    }

    @Test
    void shouldPropagateTicketWriteFailures() {
        when(eventPersistence.create(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(ticketPersistence.saveAll(any())).thenReturn(Mono.error(new IllegalStateException("unprocessed")));

        StepVerifier.create(useCase().execute(request(4)))
                .expectErrorMessage("unprocessed")
                .verify();

        verify(eventPersistence, never()).updateStatus(anyString(), any(), any(), any());
    }

    @Test
    void shouldFailWhenTheEventLeftPublishingConcurrently() {
        persistEverything();
        when(eventPersistence.updateStatus(anyString(), any(), any(), any())).thenReturn(Mono.just(false));

        StepVerifier.create(useCase().execute(request(3)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("left PUBLISHING"))
                .verify();
    }

    @Test
    void shouldPropagateEventCreationFailures() {
        when(eventPersistence.create(any())).thenReturn(Mono.error(new IllegalStateException("conflict")));

        StepVerifier.create(useCase().execute(request(3)))
                .expectErrorMessage("conflict")
                .verify();

        verifyNoInteractions(ticketPersistence);
    }

    @Test
    void shouldBeLazy() {
        useCase().execute(request(3));

        verifyNoInteractions(eventPersistence, ticketPersistence);
        assertThat(List.copyOf(writtenTickets)).isEmpty();
    }
}
