package com.ticketing.business.usecase.impl.event;

import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.onSaleEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.domain.data.EventData;
import com.ticketing.domain.data.PageData;
import com.ticketing.domain.exception.DomainValidationException;
import com.ticketing.domain.internal_request.ListEventsRequest;
import com.ticketing.enums.EventStatusEnum;
import com.ticketing.persistence.EventPersistence;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ListEventsUseCaseImplTest {

    @Mock
    private EventPersistence eventPersistence;

    @ParameterizedTest(name = "requested {0} -> {1}")
    @CsvSource({"-5, 1", "0, 1", "1, 1", "20, 20", "100, 100", "101, 100", "2147483647, 100"})
    void shouldClampTheLimitAndDelegate(int requested, int expected) {
        PageData<EventData> page = new PageData<>(List.of(onSaleEvent(10, 10)), "next");
        when(eventPersistence.findByStatus(EventStatusEnum.ON_SALE, expected, "cursor")).thenReturn(Mono.just(page));

        StepVerifier.create(new ListEventsUseCaseImpl(eventPersistence)
                        .execute(new ListEventsRequest(EventStatusEnum.ON_SALE, requested, "cursor")))
                .expectNext(page)
                .verifyComplete();
    }

    @Test
    void shouldPassAMissingCursorForTheFirstPage() {
        when(eventPersistence.findByStatus(EventStatusEnum.CLOSED, 20, null)).thenReturn(Mono.just(PageData.empty()));

        StepVerifier.create(new ListEventsUseCaseImpl(eventPersistence)
                        .execute(new ListEventsRequest(EventStatusEnum.CLOSED, 20, null)))
                .assertNext(page -> assertThat(page.hasMore()).isFalse())
                .verifyComplete();
    }

    @Test
    void shouldPropagateAMalformedCursor() {
        when(eventPersistence.findByStatus(EventStatusEnum.ON_SALE, 20, "bad"))
                .thenReturn(Mono.error(new DomainValidationException("malformed cursor")));

        StepVerifier.create(new ListEventsUseCaseImpl(eventPersistence)
                        .execute(new ListEventsRequest(EventStatusEnum.ON_SALE, 20, "bad")))
                .expectError(DomainValidationException.class)
                .verify();
    }

    @Test
    void shouldRequireAStatus() {
        StepVerifier.create(new ListEventsUseCaseImpl(eventPersistence).execute(new ListEventsRequest(null, 20, null)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(DomainValidationException.class)
                        .hasMessageContaining("status"))
                .verify();

        verifyNoInteractions(eventPersistence);
    }
}
