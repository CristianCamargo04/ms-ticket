package com.ticketing.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.enums.EventStatusEnum;
import com.ticketing.enums.OrderProcessingStatusEnum;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.enums.TicketStatusEnum;
import com.ticketing.enums.TransitionReasonEnum;
import com.ticketing.presentation.dto.message.OrderReservationMessageRequestDto;
import com.ticketing.presentation.dto.message.PaymentRequestedMessageRequestDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wire contract of the presentation DTOs: JSON binding with Jackson 3 (including single-component records) and the
 * Jakarta Validation constraints of the specification.
 */
class PresentationDtoContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-10T10:00:00Z");
    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    private static Set<String> invalidProperties(Object dto) {
        Set<ConstraintViolation<Object>> violations = validator.validate(dto);
        return violations.stream().map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private static CreateEventRequestDto validEvent() {
        return new CreateEventRequestDto("Concert", "Arena", Instant.now().plus(30, ChronoUnit.DAYS), 100,
                new BigDecimal("49.90"), "USD");
    }

    @Test
    void shouldAcceptValidCreateEventRequest() {
        assertThat(invalidProperties(validEvent())).isEmpty();
    }

    @Test
    void shouldRejectInvalidCreateEventRequest() {
        CreateEventRequestDto invalid = new CreateEventRequestDto(" ", "x".repeat(121),
                Instant.now().minus(1, ChronoUnit.DAYS), 0, new BigDecimal("1.999"), "usd");

        assertThat(invalidProperties(invalid))
                .containsExactlyInAnyOrder("name", "venue", "eventDate", "totalCapacity", "ticketPrice", "currency");
        assertThat(invalidProperties(new CreateEventRequestDto("Concert", "Arena", null, 100001,
                new BigDecimal("-0.01"), "USD")))
                .containsExactlyInAnyOrder("eventDate", "totalCapacity", "ticketPrice");
    }

    @Test
    void shouldBindAndValidatePlaceOrderRequest() {
        PlaceOrderRequestDto request = JSON.readValue("{\"quantity\":2}", PlaceOrderRequestDto.class);

        assertThat(request.quantity()).isEqualTo(2);
        assertThat(invalidProperties(request)).isEmpty();
        assertThat(invalidProperties(new PlaceOrderRequestDto(0))).containsExactly("quantity");
        assertThat(invalidProperties(new PlaceOrderRequestDto(11))).containsExactly("quantity");
    }

    @Test
    void shouldBindAndValidateConfirmOrderRequest() {
        ConfirmOrderRequestDto request = JSON.readValue("{\"paymentToken\":\"tok_visa-123\"}",
                ConfirmOrderRequestDto.class);

        assertThat(request.paymentToken()).isEqualTo("tok_visa-123");
        assertThat(invalidProperties(request)).isEmpty();
        assertThat(invalidProperties(new ConfirmOrderRequestDto("4111 1111"))).containsExactly("paymentToken");
        assertThat(invalidProperties(new ConfirmOrderRequestDto("a".repeat(129)))).containsExactly("paymentToken");
    }

    @Test
    void shouldValidateIssueComplimentaryRequest() {
        assertThat(invalidProperties(new IssueComplimentaryRequestDto("customer-9", 40))).isEmpty();
        assertThat(invalidProperties(new IssueComplimentaryRequestDto(" ", 41)))
                .containsExactlyInAnyOrder("beneficiaryId", "quantity");
    }

    @Test
    void shouldBindQueueMessages() {
        OrderReservationMessageRequestDto reservation = JSON.readValue("""
                {"orderId":"o-1","eventId":"e-1","correlationId":"corr-1","occurredAt":"2026-10-10T10:00:00Z"}
                """, OrderReservationMessageRequestDto.class);
        PaymentRequestedMessageRequestDto payment = JSON.readValue("""
                {"orderId":"o-1","paymentToken":"tok_1","correlationId":"corr-1","occurredAt":"2026-10-10T10:00:00Z"}
                """, PaymentRequestedMessageRequestDto.class);

        assertThat(reservation).isEqualTo(new OrderReservationMessageRequestDto("o-1", "e-1", "corr-1", OCCURRED_AT));
        assertThat(payment).isEqualTo(new PaymentRequestedMessageRequestDto("o-1", "tok_1", "corr-1", OCCURRED_AT));
        assertThat(invalidProperties(reservation)).isEmpty();
        assertThat(invalidProperties(payment)).isEmpty();
        assertThat(invalidProperties(new OrderReservationMessageRequestDto("", "e-1", "corr 1", null)))
                .containsExactlyInAnyOrder("orderId", "correlationId", "occurredAt");
        assertThat(invalidProperties(new PaymentRequestedMessageRequestDto("o-1", "tok 1", "corr-1", OCCURRED_AT)))
                .containsExactly("paymentToken");
    }

    @Test
    void shouldSerializeResponsesWithContractFieldNames() {
        JsonNode event = JSON.valueToTree(new EventResponseDto("e-1", "Concert", "Arena", OCCURRED_AT, 100,
                new BigDecimal("49.90"), "USD", EventStatusEnum.ON_SALE, 99, OCCURRED_AT));
        JsonNode availability = JSON.valueToTree(new AvailabilityResponseDto("e-1", 100, 90, 5, 3, 1, 1,
                OCCURRED_AT));
        JsonNode accepted = JSON.valueToTree(new OrderAcceptedResponseDto("o-1", TicketStatusEnum.AVAILABLE,
                OrderProcessingStatusEnum.QUEUED, "/api/v1/orders/o-1"));
        JsonNode status = JSON.valueToTree(new OrderStatusResponseDto("o-1", "e-1", OrderTypeEnum.STANDARD, 2,
                TicketStatusEnum.RESERVED, OrderProcessingStatusEnum.ACTIVE, null, List.of("T-000001", "T-000002"),
                OCCURRED_AT, OCCURRED_AT, OCCURRED_AT));
        JsonNode transition = JSON.valueToTree(new TicketTransitionResponseDto("T-000001",
                TicketStatusEnum.AVAILABLE, TicketStatusEnum.RESERVED, "o-1", TransitionReasonEnum.RESERVATION,
                "order-worker", OCCURRED_AT));
        JsonNode page = JSON.valueToTree(new PageResponseDto<>(List.of("a"), "next"));

        assertThat(event.propertyNames()).containsExactlyInAnyOrder("eventId", "name", "venue", "eventDate",
                "totalCapacity", "ticketPrice", "currency", "status", "availableTickets", "createdAt");
        assertThat(event.get("status").asString()).isEqualTo("ON_SALE");
        assertThat(availability.propertyNames()).containsExactlyInAnyOrder("eventId", "totalCapacity", "available",
                "reserved", "pendingConfirmation", "sold", "complimentary", "capturedAt");
        assertThat(accepted.propertyNames()).containsExactlyInAnyOrder("orderId", "status", "processingStatus",
                "statusUrl");
        assertThat(status.get("ticketIds").size()).isEqualTo(2);
        assertThat(status.get("holdExpiresAt").asString()).isEqualTo("2026-10-10T10:00:00Z");
        assertThat(transition.get("reason").asString()).isEqualTo("RESERVATION");
        assertThat(page.get("nextCursor").asString()).isEqualTo("next");
    }
}
