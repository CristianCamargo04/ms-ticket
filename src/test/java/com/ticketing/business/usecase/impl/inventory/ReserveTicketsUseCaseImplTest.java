package com.ticketing.business.usecase.impl.inventory;

import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CLOCK;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CORRELATION_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.EVENT_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.INVENTORY_RETRY_POLICY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.NOW;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.ORDER_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.ORDER_POLICY;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.RETRY_HORIZON;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.availableTicket;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.event;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.onSaleEvent;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.queuedOrder;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.reservedOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.business.usecase.impl.testing.ScriptedRandomGenerator;
import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.data.TicketData;
import com.ticketing.domain.exception.InventoryConflictException;
import com.ticketing.domain.exception.OrderNotFoundException;
import com.ticketing.domain.internal_request.ReserveTicketsRequest;
import com.ticketing.domain.inventory.InventoryWriteOutcome;
import com.ticketing.domain.inventory.IssueComplimentaryCommand;
import com.ticketing.domain.inventory.ReserveInventoryCommand;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.persistence.EventPersistence;
import com.ticketing.persistence.InventoryTransactionPersistence;
import com.ticketing.persistence.OrderPersistence;
import com.ticketing.persistence.TicketPersistence;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ReserveTicketsUseCaseImplTest {
    private static final ReserveTicketsRequest REQUEST = new ReserveTicketsRequest(ORDER_ID, CORRELATION_ID);

    private static final int WINDOW = 2 * ORDER_POLICY.candidateFactor();

    @Mock
    private OrderPersistence orderPersistence;

    @Mock
    private EventPersistence eventPersistence;

    @Mock
    private TicketPersistence ticketPersistence;

    @Mock
    private InventoryTransactionPersistence inventoryTransactionPersistence;

    @Captor
    private ArgumentCaptor<ReserveInventoryCommand> reserveCommand;

    @Captor
    private ArgumentCaptor<IssueComplimentaryCommand> complimentaryCommand;

    private ReserveTicketsUseCaseImpl useCase(RandomGenerator randomGenerator) {
        return new ReserveTicketsUseCaseImpl(orderPersistence, eventPersistence, ticketPersistence,
                inventoryTransactionPersistence, ORDER_POLICY, INVENTORY_RETRY_POLICY, CLOCK, randomGenerator);
    }

    private ReserveTicketsUseCaseImpl useCase() {
        return useCase(new ScriptedRandomGenerator(41, 3, 2));
    }

    private static Flux<TicketData> available(int fromSequence, int count) {
        return Flux.fromIterable(IntStream.range(fromSequence, fromSequence + count)
                .mapToObj(sequence -> availableTicket(sequence))
                .toList());
    }

    private static OrderData reservedWith(OrderData order, List<String> ticketIds) {
        return order.withReservation(ticketIds, ORDER_POLICY.holdExpiresAt(NOW), NOW);
    }

    private static OrderData rejected(OrderData order, String reason) {
        return order.withProcessingStatus(OrderProcessingStatusEnum.REJECTED, reason, NOW);
    }

    private void givenOrder(OrderData order) {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(order));
    }

    private void givenEvent(EventData event) {
        when(eventPersistence.findById(EVENT_ID)).thenReturn(Mono.just(event));
    }

    @Test
    void shouldReserveARandomSampleOfTheCandidatesStartingAtARandomTicket() {
        OrderData queued = queuedOrder(2);
        List<String> expected = List.of("T-000042", "T-000045");
        givenOrder(queued);
        givenEvent(onSaleEvent(100, 100));
        when(ticketPersistence.findAvailable(EVENT_ID, "T-000042", WINDOW)).thenReturn(available(42, 6));
        when(inventoryTransactionPersistence.reserve(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.Applied(reservedWith(queued, expected))));

        StepVerifier.create(useCase().execute(REQUEST))
                .assertNext(order -> {
                    assertThat(order.processingStatus()).isEqualTo(OrderProcessingStatusEnum.ACTIVE);
                    assertThat(order.ticketStatus()).isEqualTo(TicketStatusEnum.RESERVED);
                    assertThat(order.ticketIds()).containsExactlyElementsOf(expected);
                })
                .verifyComplete();

        verify(inventoryTransactionPersistence).reserve(reserveCommand.capture());
        ReserveInventoryCommand command = reserveCommand.getValue();
        assertThat(command.order()).isEqualTo(queued);
        assertThat(command.ticketIds()).containsExactlyElementsOf(expected);
        assertThat(command.holdExpiresAt()).isEqualTo(ORDER_POLICY.holdExpiresAt(NOW));
        assertThat(command.now()).isEqualTo(NOW);
        assertThat(command.actor()).isEqualTo(ReserveTicketsUseCaseImpl.ACTOR);
        verify(ticketPersistence, never()).findAvailable(EVENT_ID, ReserveTicketsUseCaseImpl.FROM_FIRST_TICKET,
                WINDOW);
    }

    @Test
    void shouldIssueTheTicketsOfAComplimentaryOrder() {
        OrderData queued = queuedOrder(OrderTypeEnum.COMPLIMENTARY, 2);
        List<String> expected = List.of("T-000042", "T-000045");
        givenOrder(queued);
        givenEvent(onSaleEvent(100, 100));
        when(ticketPersistence.findAvailable(EVENT_ID, "T-000042", WINDOW)).thenReturn(available(42, 6));
        when(inventoryTransactionPersistence.issueComplimentary(any())).thenReturn(Mono.just(
                new InventoryWriteOutcome.Applied(queued.withComplimentaryIssued(expected, NOW))));

        StepVerifier.create(useCase().execute(REQUEST))
                .assertNext(order -> {
                    assertThat(order.processingStatus()).isEqualTo(OrderProcessingStatusEnum.COMPLETED);
                    assertThat(order.ticketStatus()).isEqualTo(TicketStatusEnum.COMPLIMENTARY);
                })
                .verifyComplete();

        verify(inventoryTransactionPersistence).issueComplimentary(complimentaryCommand.capture());
        assertThat(complimentaryCommand.getValue().ticketIds()).containsExactlyElementsOf(expected);
        assertThat(complimentaryCommand.getValue().actor()).isEqualTo(ReserveTicketsUseCaseImpl.ACTOR);
        verify(inventoryTransactionPersistence, never()).reserve(any());
    }

    @Test
    void shouldCompleteTheCandidatesFromTheFirstTicketWhenTheRandomStartIsNearTheEnd() {
        OrderData queued = queuedOrder(3);
        int window = 3 * ORDER_POLICY.candidateFactor();
        List<String> expected = List.of("T-000001", "T-000099", "T-000100");
        givenOrder(queued);
        givenEvent(onSaleEvent(100, 50));
        when(ticketPersistence.findAvailable(EVENT_ID, "T-000099", window)).thenReturn(available(99, 2));
        when(ticketPersistence.findAvailable(EVENT_ID, ReserveTicketsUseCaseImpl.FROM_FIRST_TICKET, window))
                .thenReturn(Flux.concat(available(1, 1), available(99, 1)));
        when(inventoryTransactionPersistence.reserve(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.Applied(reservedWith(queued, expected))));

        StepVerifier.create(useCase(new ScriptedRandomGenerator(98, 0)).execute(REQUEST))
                .expectNextCount(1)
                .verifyComplete();

        verify(inventoryTransactionPersistence).reserve(reserveCommand.capture());
        assertThat(reserveCommand.getValue().ticketIds()).containsExactlyElementsOf(expected);
    }

    @Test
    void shouldIgnoreStaleAndDuplicatedCandidates() {
        OrderData queued = queuedOrder(2);
        TicketData stale = availableTicket(43).withState(TicketStatusEnum.RESERVED, "other", NOW, NOW);
        givenOrder(queued);
        givenEvent(onSaleEvent(100, 100));
        when(ticketPersistence.findAvailable(EVENT_ID, "T-000001", WINDOW))
                .thenReturn(Flux.just(availableTicket(1), stale, availableTicket(1), availableTicket(2)));
        when(inventoryTransactionPersistence.reserve(any())).thenReturn(Mono.just(
                new InventoryWriteOutcome.Applied(reservedWith(queued, List.of("T-000001", "T-000002")))));

        StepVerifier.create(useCase(new ScriptedRandomGenerator(0)).execute(REQUEST))
                .expectNextCount(1)
                .verifyComplete();

        verify(inventoryTransactionPersistence).reserve(reserveCommand.capture());
        assertThat(reserveCommand.getValue().ticketIds()).containsExactly("T-000001", "T-000002");
    }

    @Test
    void shouldRetryWithAFreshReadWhenTheIndexHasTooFewCandidates() {
        OrderData queued = queuedOrder(2);
        givenOrder(queued);
        givenEvent(onSaleEvent(100, 10));
        when(ticketPersistence.findAvailable(EVENT_ID, "T-000001", WINDOW))
                .thenReturn(Flux.empty())
                .thenReturn(available(1, 6));
        when(ticketPersistence.findAvailable(EVENT_ID, ReserveTicketsUseCaseImpl.FROM_FIRST_TICKET, WINDOW))
                .thenReturn(available(1, 1));
        when(inventoryTransactionPersistence.reserve(any())).thenReturn(Mono.just(
                new InventoryWriteOutcome.Applied(reservedWith(queued, List.of("T-000001", "T-000002")))));

        StepVerifier.withVirtualTime(() -> useCase(new ScriptedRandomGenerator(0)).execute(REQUEST))
                .expectSubscription()
                .thenAwait(RETRY_HORIZON)
                .expectNextCount(1)
                .verifyComplete();

        verify(eventPersistence, times(2)).findById(EVENT_ID);
        verify(inventoryTransactionPersistence, times(1)).reserve(any());
    }

    @Test
    void shouldRetryAStaleSelectionWithANewSelection() {
        OrderData queued = queuedOrder(2);
        givenOrder(queued);
        givenEvent(onSaleEvent(100, 100));
        when(ticketPersistence.findAvailable(eq(EVENT_ID), anyString(), eq(WINDOW))).thenReturn(available(1, 6));
        when(inventoryTransactionPersistence.reserve(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.StaleTicketSelection()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.Applied(
                        reservedWith(queued, List.of("T-000001", "T-000002")))));

        StepVerifier.withVirtualTime(() -> useCase().execute(REQUEST))
                .expectSubscription()
                .thenAwait(RETRY_HORIZON)
                .assertNext(order -> assertThat(order.ticketStatus()).isEqualTo(TicketStatusEnum.RESERVED))
                .verifyComplete();

        verify(eventPersistence, times(2)).findById(EVENT_ID);
        verify(inventoryTransactionPersistence, times(2)).reserve(any());
        verify(orderPersistence, times(1)).findById(ORDER_ID);
    }

    @Test
    void shouldRetryATransactionConflict() {
        OrderData queued = queuedOrder(2);
        givenOrder(queued);
        givenEvent(onSaleEvent(100, 100));
        when(ticketPersistence.findAvailable(eq(EVENT_ID), anyString(), eq(WINDOW))).thenReturn(available(1, 6));
        when(inventoryTransactionPersistence.reserve(any()))
                .thenReturn(Mono.error(new InventoryConflictException("TransactionConflict")))
                .thenReturn(Mono.just(new InventoryWriteOutcome.Applied(
                        reservedWith(queued, List.of("T-000001", "T-000002")))));

        StepVerifier.withVirtualTime(() -> useCase().execute(REQUEST))
                .expectSubscription()
                .thenAwait(RETRY_HORIZON)
                .expectNextCount(1)
                .verifyComplete();

        verify(inventoryTransactionPersistence, times(2)).reserve(any());
    }

    @Test
    void shouldPropagateTheConflictOnceTheRetriesAreExhausted() {
        givenOrder(queuedOrder(2));
        givenEvent(onSaleEvent(100, 100));
        when(ticketPersistence.findAvailable(eq(EVENT_ID), anyString(), eq(WINDOW))).thenReturn(available(1, 6));
        when(inventoryTransactionPersistence.reserve(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.StaleTicketSelection()));

        StepVerifier.withVirtualTime(() -> useCase().execute(REQUEST))
                .expectSubscription()
                .thenAwait(RETRY_HORIZON)
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(InventoryConflictException.class)
                        .hasMessageContaining("Stale ticket selection"))
                .verify();

        verify(inventoryTransactionPersistence, times(INVENTORY_RETRY_POLICY.maxAttempts() + 1)).reserve(any());
    }

    @Test
    void shouldNotRetryNonTransientFailures() {
        givenOrder(queuedOrder(2));
        givenEvent(onSaleEvent(100, 100));
        when(ticketPersistence.findAvailable(eq(EVENT_ID), anyString(), eq(WINDOW))).thenReturn(available(1, 6));
        when(inventoryTransactionPersistence.reserve(any())).thenReturn(Mono.error(new IllegalStateException("bug")));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectErrorMessage("bug")
                .verify();

        verify(inventoryTransactionPersistence, times(1)).reserve(any());
    }

    @Test
    void shouldRejectTheOrderWhenTheCounterConditionFails() {
        OrderData queued = queuedOrder(2);
        OrderData soldOut = rejected(queued, ReserveTicketsUseCaseImpl.REASON_SOLD_OUT);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(queued)).thenReturn(Mono.just(soldOut));
        givenEvent(onSaleEvent(100, 100));
        when(ticketPersistence.findAvailable(eq(EVENT_ID), anyString(), eq(WINDOW))).thenReturn(available(1, 6));
        when(inventoryTransactionPersistence.reserve(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.InsufficientInventory()));
        when(orderPersistence.transitionProcessingStatus(ORDER_ID, OrderProcessingStatusEnum.QUEUED,
                OrderProcessingStatusEnum.REJECTED, "SOLD_OUT", NOW)).thenReturn(Mono.just(true));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(soldOut)
                .verifyComplete();
    }

    @Test
    void shouldRejectTheOrderWhenTooFewTicketsAreAvailable() {
        OrderData queued = queuedOrder(3);
        OrderData soldOut = rejected(queued, "SOLD_OUT");
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(queued)).thenReturn(Mono.just(soldOut));
        givenEvent(onSaleEvent(100, 2));
        when(orderPersistence.transitionProcessingStatus(ORDER_ID, OrderProcessingStatusEnum.QUEUED,
                OrderProcessingStatusEnum.REJECTED, "SOLD_OUT", NOW)).thenReturn(Mono.just(true));

        StepVerifier.create(useCase().execute(REQUEST))
                .assertNext(order -> {
                    assertThat(order.processingStatus()).isEqualTo(OrderProcessingStatusEnum.REJECTED);
                    assertThat(order.reason()).isEqualTo("SOLD_OUT");
                })
                .verifyComplete();

        verifyNoInteractions(ticketPersistence, inventoryTransactionPersistence);
    }

    @Test
    void shouldRejectTheOrderWhenTheEventIsNotOnSale() {
        OrderData queued = queuedOrder(2);
        OrderData notOnSale = rejected(queued, "EVENT_NOT_ON_SALE");
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(queued)).thenReturn(Mono.just(notOnSale));
        givenEvent(event(EventStatusEnum.CLOSED, 100, 100));
        when(orderPersistence.transitionProcessingStatus(ORDER_ID, OrderProcessingStatusEnum.QUEUED,
                OrderProcessingStatusEnum.REJECTED, ReserveTicketsUseCaseImpl.REASON_EVENT_NOT_ON_SALE, NOW))
                .thenReturn(Mono.just(true));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(notOnSale)
                .verifyComplete();

        verifyNoInteractions(ticketPersistence, inventoryTransactionPersistence);
    }

    @Test
    void shouldRejectTheOrderWhenTheEventDoesNotExist() {
        OrderData queued = queuedOrder(2);
        OrderData notOnSale = rejected(queued, "EVENT_NOT_ON_SALE");
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(queued)).thenReturn(Mono.just(notOnSale));
        when(eventPersistence.findById(EVENT_ID)).thenReturn(Mono.empty());
        when(orderPersistence.transitionProcessingStatus(ORDER_ID, OrderProcessingStatusEnum.QUEUED,
                OrderProcessingStatusEnum.REJECTED, "EVENT_NOT_ON_SALE", NOW)).thenReturn(Mono.just(true));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(notOnSale)
                .verifyComplete();
    }

    @Test
    void shouldReturnTheCurrentOrderWhenItWasProcessedBeforeTheRejection() {
        OrderData queued = queuedOrder(2);
        OrderData reservedByAnotherDelivery = reservedOrder(2);
        when(orderPersistence.findById(ORDER_ID))
                .thenReturn(Mono.just(queued))
                .thenReturn(Mono.just(reservedByAnotherDelivery));
        givenEvent(onSaleEvent(100, 1));
        when(orderPersistence.transitionProcessingStatus(anyString(), any(), any(), anyString(), any()))
                .thenReturn(Mono.just(false));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(reservedByAnotherDelivery)
                .verifyComplete();
    }

    @Test
    void shouldReturnTheCurrentOrderWhenAnotherDeliveryWonTheTransaction() {
        OrderData queued = queuedOrder(2);
        OrderData winner = reservedOrder(2);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(queued)).thenReturn(Mono.just(winner));
        givenEvent(onSaleEvent(100, 100));
        when(ticketPersistence.findAvailable(eq(EVENT_ID), anyString(), eq(WINDOW))).thenReturn(available(1, 6));
        when(inventoryTransactionPersistence.reserve(any()))
                .thenReturn(Mono.just(new InventoryWriteOutcome.OrderStateChanged()));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(winner)
                .verifyComplete();
    }

    @Test
    void shouldIgnoreADuplicateDeliveryOfAProcessedOrder() {
        OrderData processed = reservedOrder(2);
        givenOrder(processed);

        StepVerifier.create(useCase().execute(REQUEST))
                .expectNext(processed)
                .verifyComplete();

        verifyNoInteractions(eventPersistence, ticketPersistence, inventoryTransactionPersistence);
    }

    @Test
    void shouldFailWithoutRetryWhenTheOrderDoesNotExist() {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.empty());

        StepVerifier.create(useCase().execute(REQUEST))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OrderNotFoundException.class)
                        .hasMessageContaining(ORDER_ID))
                .verify();

        verify(orderPersistence, times(1)).findById(ORDER_ID);
        verifyNoInteractions(eventPersistence);
    }

    @Test
    void shouldFailWhenTheRejectedOrderCannotBeReloaded() {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(queuedOrder(2))).thenReturn(Mono.empty());
        givenEvent(onSaleEvent(100, 0));
        when(orderPersistence.transitionProcessingStatus(anyString(), any(), any(), anyString(), any()))
                .thenReturn(Mono.just(true));

        StepVerifier.create(useCase().execute(REQUEST))
                .expectError(OrderNotFoundException.class)
                .verify();
    }

    @Test
    void shouldBeLazy() {
        useCase().execute(REQUEST);

        verifyNoInteractions(orderPersistence, eventPersistence, ticketPersistence, inventoryTransactionPersistence);
    }
}
