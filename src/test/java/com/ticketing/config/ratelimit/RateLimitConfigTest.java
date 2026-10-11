package com.ticketing.config.ratelimit;

import static com.ticketing.presentation.impl.TestJwts.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ticketing.business.usecase.CancelOrderUseCase;
import com.ticketing.business.usecase.ConfirmOrderUseCase;
import com.ticketing.business.usecase.GetOrderStatusUseCase;
import com.ticketing.business.usecase.PlaceOrderUseCase;
import com.ticketing.config.properties.TicketingProperties;
import com.ticketing.config.web.ProblemDetailResponseWriter;
import com.ticketing.domain.data.OrderData;
import com.ticketing.enums.OrderTypeEnum;
import com.ticketing.presentation.OrderRestController;
import com.ticketing.presentation.impl.OrderRestControllerImpl;
import com.ticketing.presentation.impl.RestControllerTestConfig;
import com.ticketing.presentation.impl.error.ProblemDetailFactory;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

@WebFluxTest(controllers = OrderRestControllerImpl.class, properties = {
    "ticketing.rate-limit.capacity=2",
    "ticketing.rate-limit.refill-tokens=1",
    "ticketing.rate-limit.refill-period=1m"})
@Import({RestControllerTestConfig.class, RateLimitConfig.class})
class RateLimitConfigTest {
    private static final Clock CLOCK = Clock.fixed(RestControllerTestConfig.NOW, ZoneOffset.UTC);

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private PlaceOrderUseCase placeOrderUseCase;

    @MockitoBean
    private GetOrderStatusUseCase getOrderStatusUseCase;

    @MockitoBean
    private ConfirmOrderUseCase confirmOrderUseCase;

    @MockitoBean
    private CancelOrderUseCase cancelOrderUseCase;

    @MockitoBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(RateLimitConfig.class)
                .withBean(TicketingProperties.class,
                        () -> new Binder().bindOrCreate("ticketing", TicketingProperties.class))
                .withBean(Clock.class, () -> CLOCK)
                .withBean(ProblemDetailFactory.class, () -> new ProblemDetailFactory(CLOCK))
                .withBean(ProblemDetailResponseWriter.class,
                        () -> new ProblemDetailResponseWriter(JsonMapper.builder().build()));
    }

    private WebTestClient.ResponseSpec placeOrder(String subject) {
        return webTestClient.mutateWith(customer(subject)).post().uri("/api/v1/events/e-1/orders")
                .header(OrderRestController.IDEMPOTENCY_KEY_HEADER, "key-" + subject)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"quantity\":1}")
                .exchange();
    }

    @Test
    void shouldRejectCustomersOverTheirQuotaWithAProblem() {
        given(placeOrderUseCase.execute(any())).willReturn(Mono.just(OrderData.newQueued("o-1", "e-1",
                "rate-1", OrderTypeEnum.STANDARD, 1, "hash", RestControllerTestConfig.NOW,
                RestControllerTestConfig.NOW)));

        placeOrder("rate-1").expectStatus().isAccepted()
                .expectHeader().valueEquals(RateLimitWebFilter.REMAINING_HEADER, "1");
        placeOrder("rate-1").expectStatus().isAccepted()
                .expectHeader().valueEquals(RateLimitWebFilter.REMAINING_HEADER, "0");
        placeOrder("rate-1").expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "60")
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo("RATE_LIMIT_EXCEEDED")
                .jsonPath("$.title").isEqualTo("RATE_LIMIT_EXCEEDED")
                .jsonPath("$.correlationId").isNotEmpty();
        placeOrder("rate-2").expectStatus().isAccepted();

        verify(placeOrderUseCase, times(3)).execute(any());
    }

    @Test
    void shouldNotLimitReads() {
        given(getOrderStatusUseCase.execute(any())).willReturn(Mono.just(OrderData.newQueued("o-1", "e-1",
                "rate-3", OrderTypeEnum.STANDARD, 1, "hash", RestControllerTestConfig.NOW,
                RestControllerTestConfig.NOW)));

        for (int i = 0; i < 4; i++) {
            webTestClient.mutateWith(customer("rate-3")).get().uri("/api/v1/orders/o-1")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().doesNotExist(RateLimitWebFilter.REMAINING_HEADER);
        }
    }

    @Test
    void shouldRegisterTheFilterByDefault() {
        contextRunner().run(context -> assertThat(context).hasSingleBean(RateLimitWebFilter.class));
    }

    @Test
    void shouldNotRegisterTheFilterWhenDisabled() {
        contextRunner().withPropertyValues("ticketing.rate-limit.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RateLimitWebFilter.class));
    }
}
