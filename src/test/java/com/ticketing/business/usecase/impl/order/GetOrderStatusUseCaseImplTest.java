package com.ticketing.business.usecase.impl.order;

import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.CUSTOMER_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.ORDER_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.OTHER_CUSTOMER_ID;
import static com.ticketing.business.usecase.impl.testing.BusinessFixtures.reservedOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.exception.OrderNotFoundException;
import com.ticketing.domain.internal_request.GetOrderRequest;
import com.ticketing.persistence.OrderPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class GetOrderStatusUseCaseImplTest {
    @Mock
    private OrderPersistence orderPersistence;

    @Test
    void shouldReturnTheOrderToItsOwner() {
        OrderData order = reservedOrder(2);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(order));

        StepVerifier.create(new GetOrderStatusUseCaseImpl(orderPersistence)
                        .execute(new GetOrderRequest(ORDER_ID, CUSTOMER_ID, false)))
                .expectNext(order)
                .verifyComplete();
    }

    @Test
    void shouldReturnAnyOrderToAPrivilegedRequester() {
        OrderData order = reservedOrder(2);
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(order));

        StepVerifier.create(new GetOrderStatusUseCaseImpl(orderPersistence)
                        .execute(new GetOrderRequest(ORDER_ID, "admin-1", true)))
                .expectNext(order)
                .verifyComplete();
    }

    @Test
    void shouldHideTheOrderOfAnotherCustomer() {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.just(reservedOrder(2)));

        StepVerifier.create(new GetOrderStatusUseCaseImpl(orderPersistence)
                        .execute(new GetOrderRequest(ORDER_ID, OTHER_CUSTOMER_ID, false)))
                .expectError(OrderNotFoundException.class)
                .verify();
    }

    @Test
    void shouldFailWhenTheOrderDoesNotExist() {
        when(orderPersistence.findById(ORDER_ID)).thenReturn(Mono.empty());

        StepVerifier.create(new GetOrderStatusUseCaseImpl(orderPersistence)
                        .execute(new GetOrderRequest(ORDER_ID, CUSTOMER_ID, true)))
                .expectError(OrderNotFoundException.class)
                .verify();
    }

    @Test
    void shouldBeLazy() {
        new GetOrderStatusUseCaseImpl(orderPersistence).execute(new GetOrderRequest(ORDER_ID, CUSTOMER_ID, false));

        verifyNoInteractions(orderPersistence);
    }
}
