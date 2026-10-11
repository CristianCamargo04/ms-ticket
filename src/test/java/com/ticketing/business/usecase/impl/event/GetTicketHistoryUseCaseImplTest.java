package com.ticketing.business.usecase.impl.event;

import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.EVENT_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.NOW;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.ORDER_ID;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.domain.data.TicketTransitionData;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import com.ticketing.persistence.TicketTransitionPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class GetTicketHistoryUseCaseImplTest {

    private static final String TICKET_ID = "T-000001";

    @Mock
    private TicketTransitionPersistence ticketTransitionPersistence;

    @Test
    void shouldReturnTheTransitionsInChronologicalOrder() {
        TicketTransitionData reserved = new TicketTransitionData(EVENT_ID, TICKET_ID, "tr-1",
                TicketStatusEnum.AVAILABLE, TicketStatusEnum.RESERVED, ORDER_ID, TransitionReasonEnum.RESERVATION,
                "order-worker", NOW.minusSeconds(60));
        TicketTransitionData expired = new TicketTransitionData(EVENT_ID, TICKET_ID, "tr-2",
                TicketStatusEnum.RESERVED, TicketStatusEnum.AVAILABLE, ORDER_ID, TransitionReasonEnum.HOLD_EXPIRED,
                "reservation-expiration-sweeper", NOW);
        when(ticketTransitionPersistence.findByTicket(EVENT_ID, TICKET_ID)).thenReturn(Flux.just(reserved, expired));

        StepVerifier.create(new GetTicketHistoryUseCaseImpl(ticketTransitionPersistence).execute(EVENT_ID, TICKET_ID))
                .expectNext(reserved, expired)
                .verifyComplete();
    }

    @Test
    void shouldReturnAnEmptyHistory() {
        when(ticketTransitionPersistence.findByTicket(EVENT_ID, TICKET_ID)).thenReturn(Flux.empty());

        StepVerifier.create(new GetTicketHistoryUseCaseImpl(ticketTransitionPersistence).execute(EVENT_ID, TICKET_ID))
                .verifyComplete();
    }

    @Test
    void shouldBeLazy() {
        new GetTicketHistoryUseCaseImpl(ticketTransitionPersistence).execute(EVENT_ID, TICKET_ID);

        verifyNoInteractions(ticketTransitionPersistence);
    }
}
