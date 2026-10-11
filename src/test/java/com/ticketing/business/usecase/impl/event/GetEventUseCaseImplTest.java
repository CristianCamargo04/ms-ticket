package com.ticketing.business.usecase.impl.event;

import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.EVENT_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.onSaleEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.cache.EventCacheService;
import com.ticketing.domain.data.EventData;
import com.ticketing.domain.exception.EventNotFoundException;
import com.ticketing.persistence.EventPersistence;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class GetEventUseCaseImplTest {
    @Mock
    private EventCacheService eventCacheService;

    @Mock
    private EventPersistence eventPersistence;

    private GetEventUseCaseImpl useCase() {
        return new GetEventUseCaseImpl(eventCacheService, eventPersistence);
    }

    @Test
    void shouldReturnTheEventThroughTheCacheLoadingItFromPersistence() {
        EventData event = onSaleEvent(10, 10);
        when(eventCacheService.get(eq(EVENT_ID), any())).thenReturn(Mono.just(event));
        when(eventPersistence.findById(EVENT_ID)).thenReturn(Mono.just(event));

        StepVerifier.create(useCase().execute(EVENT_ID))
                .expectNext(event)
                .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Function<String, Mono<EventData>>> loader = ArgumentCaptor.forClass(Function.class);
        verify(eventCacheService).get(eq(EVENT_ID), loader.capture());
        StepVerifier.create(loader.getValue().apply(EVENT_ID))
                .expectNext(event)
                .verifyComplete();
    }

    @Test
    void shouldFailWhenTheEventDoesNotExist() {
        when(eventCacheService.get(eq(EVENT_ID), any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase().execute(EVENT_ID))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(EventNotFoundException.class)
                        .hasMessageContaining(EVENT_ID))
                .verify();
    }

    @Test
    void shouldBeLazy() {
        useCase().execute(EVENT_ID);

        verifyNoInteractions(eventCacheService, eventPersistence);
    }
}
