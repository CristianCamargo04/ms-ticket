package com.ticketing.business.usecase.impl.event;

import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CLOCK;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.EVENT_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.NOW;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.onSaleEvent;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.domain.data.AvailabilitySnapshot;
import com.ticketing.domain.exception.EventNotFoundException;
import com.ticketing.persistence.EventPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class GetEventAvailabilityUseCaseImplTest {

    @Mock
    private EventPersistence eventPersistence;

    @Test
    void shouldReturnTheCountersCapturedNow() {
        when(eventPersistence.findById(EVENT_ID)).thenReturn(Mono.just(onSaleEvent(100, 60)));

        StepVerifier.create(new GetEventAvailabilityUseCaseImpl(eventPersistence, CLOCK).execute(EVENT_ID))
                .expectNext(new AvailabilitySnapshot(EVENT_ID, 100, 60, 40, 0, 0, 0, NOW))
                .verifyComplete();
    }

    @Test
    void shouldFailWhenTheEventDoesNotExist() {
        when(eventPersistence.findById(EVENT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(new GetEventAvailabilityUseCaseImpl(eventPersistence, CLOCK).execute(EVENT_ID))
                .expectError(EventNotFoundException.class)
                .verify();
    }

    @Test
    void shouldBeLazy() {
        new GetEventAvailabilityUseCaseImpl(eventPersistence, CLOCK).execute(EVENT_ID);

        verifyNoInteractions(eventPersistence);
    }
}
