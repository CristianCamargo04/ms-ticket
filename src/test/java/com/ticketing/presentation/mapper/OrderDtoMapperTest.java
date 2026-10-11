package com.ticketing.presentation.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.domain.data.OrderData;
import com.ticketing.domain.internal_request.CancelOrderRequest;
import com.ticketing.domain.internal_request.ConfirmOrderRequest;
import com.ticketing.domain.internal_request.GetOrderRequest;
import com.ticketing.domain.internal_request.IssueComplimentaryRequest;
import com.ticketing.domain.internal_request.PlaceOrderRequest;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.presentation.context.RequestContext;
import com.ticketing.presentation.dto.ConfirmOrderRequestDto;
import com.ticketing.presentation.dto.IssueComplimentaryRequestDto;
import com.ticketing.presentation.dto.OrderAcceptedResponseDto;
import com.ticketing.presentation.dto.OrderStatusResponseDto;
import com.ticketing.presentation.dto.PlaceOrderRequestDto;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrderDtoMapperTest {
    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
    private static final RequestContext CUSTOMER =
            new RequestContext("customer-1", Set.of("ROLE_CUSTOMER"), "corr-1", "10.0.0.1");
    private static final RequestContext ADMIN = new RequestContext("admin-1", Set.of("ROLE_ADMIN"), "corr-2", null);

    private final OrderDtoMapper mapper = new OrderDtoMapper();

    private static OrderData reservedOrder() {
        return OrderData.newQueued("o-1", "e-1", "customer-1", OrderTypeEnum.STANDARD, 2, "hash",
                NOW.plusSeconds(30), NOW).withReservation(List.of("T-000001", "T-000002"), NOW.plusSeconds(600), NOW);
    }

    @Test
    void shouldBuildPlaceOrderRequestForTheAuthenticatedCustomer() {
        assertThat(mapper.toPlaceOrderRequest("e-1", new PlaceOrderRequestDto(3), CUSTOMER, "key-12345", "h"))
                .isEqualTo(new PlaceOrderRequest("e-1", "customer-1", 3, "key-12345", "h", "corr-1"));
        assertThatNullPointerException()
                .isThrownBy(() -> mapper.toPlaceOrderRequest("e-1", null, CUSTOMER, "key-12345", "h"));
        assertThatNullPointerException()
                .isThrownBy(() -> mapper.toPlaceOrderRequest("e-1", new PlaceOrderRequestDto(1), null, "k", "h"));
    }

    @Test
    void shouldBuildComplimentaryRequestIssuedByTheAuthenticatedAdministrator() {
        assertThat(mapper.toIssueComplimentaryRequest("e-1", new IssueComplimentaryRequestDto("vip-1", 4), ADMIN,
                "key-12345", "h"))
                .isEqualTo(new IssueComplimentaryRequest("e-1", "vip-1", 4, "admin-1", "key-12345", "h", "corr-2"));
        assertThatNullPointerException().isThrownBy(() -> mapper.toIssueComplimentaryRequest("e-1", null, ADMIN,
                "k", "h"));
        assertThatNullPointerException().isThrownBy(() -> mapper.toIssueComplimentaryRequest("e-1",
                new IssueComplimentaryRequestDto("vip-1", 1), null, "k", "h"));
    }

    @Test
    void shouldGrantPrivilegedReadsToAdministratorsOnly() {
        assertThat(mapper.toGetOrderRequest("o-1", CUSTOMER)).isEqualTo(new GetOrderRequest("o-1", "customer-1",
                false));
        assertThat(mapper.toGetOrderRequest("o-1", ADMIN)).isEqualTo(new GetOrderRequest("o-1", "admin-1", true));
        assertThatNullPointerException().isThrownBy(() -> mapper.toGetOrderRequest("o-1", null));
    }

    @Test
    void shouldBuildConfirmAndCancelRequestsForTheCaller() {
        assertThat(mapper.toConfirmOrderRequest("o-1", new ConfirmOrderRequestDto("tok_1"), CUSTOMER))
                .isEqualTo(new ConfirmOrderRequest("o-1", "customer-1", "tok_1", "corr-1"));
        assertThat(mapper.toCancelOrderRequest("o-1", CUSTOMER))
                .isEqualTo(new CancelOrderRequest("o-1", "customer-1"));
        assertThatNullPointerException().isThrownBy(() -> mapper.toConfirmOrderRequest("o-1", null, CUSTOMER));
        assertThatNullPointerException().isThrownBy(() -> mapper.toConfirmOrderRequest("o-1",
                new ConfirmOrderRequestDto("tok_1"), null));
        assertThatNullPointerException().isThrownBy(() -> mapper.toCancelOrderRequest("o-1", null));
    }

    @Test
    void shouldMapAcceptedOrderWithItsStatusUrl() {
        OrderData queued = OrderData.newQueued("o-1", "e-1", "customer-1", OrderTypeEnum.STANDARD, 2, "hash",
                NOW.plusSeconds(30), NOW);

        assertThat(mapper.toAcceptedResponse(queued)).isEqualTo(new OrderAcceptedResponseDto("o-1",
                TicketStatusEnum.AVAILABLE, OrderProcessingStatusEnum.QUEUED, "/api/v1/orders/o-1"));
        assertThatNullPointerException().isThrownBy(() -> mapper.toAcceptedResponse(null));
    }

    @Test
    void shouldMapOrderStatus() {
        assertThat(mapper.toStatusResponse(reservedOrder())).isEqualTo(new OrderStatusResponseDto("o-1", "e-1",
                OrderTypeEnum.STANDARD, 2, TicketStatusEnum.RESERVED, OrderProcessingStatusEnum.ACTIVE, null,
                List.of("T-000001", "T-000002"), NOW.plusSeconds(600), NOW, NOW));
        assertThatNullPointerException().isThrownBy(() -> mapper.toStatusResponse(null));
    }

    @Test
    void shouldEncodeTheStatusUri() {
        assertThat(mapper.statusUri("o-1")).isEqualTo(URI.create("/api/v1/orders/o-1"));
        assertThat(mapper.statusUri("a b/c")).isEqualTo(URI.create("/api/v1/orders/a%20b%2Fc"));
        assertThatNullPointerException().isThrownBy(() -> mapper.statusUri(null));
    }
}
