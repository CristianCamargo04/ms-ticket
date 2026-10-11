package com.ticketing.support;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/**
 * A tiny in-process payment gateway (Reactor Netty) with the contract of {@code docker/wiremock}, plus the JWK set of
 * the {@link TestJwtIssuer}.
 *
 * <ul>
 *   <li>{@code POST /v1/charges}: {@code 201} {@code APPROVED} for a {@code paymentToken} starting with
 *       {@value #APPROVED_PREFIX}, {@code DECLINED / INSUFFICIENT_FUNDS} otherwise; {@code 400} without an
 *       {@code Idempotency-Key}. Every charge is recorded (idempotency key, {@code Authorization} header, token).</li>
 *   <li>{@code GET /v1/charges/{key}}: {@code 404} (no charge state, like the WireMock mock).</li>
 *   <li>{@code GET /jwks}: the issuer's public keys.</li>
 * </ul>
 */
public final class PaymentGatewayStub implements AutoCloseable {

    /** Payment tokens starting with this prefix are approved. */
    public static final String APPROVED_PREFIX = "tok_approved";

    private static final Pattern PAYMENT_TOKEN = Pattern.compile("\"paymentToken\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern ORDER_ID = Pattern.compile("\"orderId\"\\s*:\\s*\"([^\"]*)\"");
    private static final String JSON = "application/json";

    private final List<RecordedCharge> charges = new CopyOnWriteArrayList<>();
    private final String jwkSetJson;
    private final DisposableServer server;

    /**
     * A charge request received by the stub.
     *
     * @param idempotencyKey {@code Idempotency-Key} header
     * @param authorization  {@code Authorization} header, {@code null} when absent
     * @param orderId        order id of the body
     * @param paymentToken   payment token of the body
     */
    public record RecordedCharge(String idempotencyKey, String authorization, String orderId, String paymentToken) {
    }

    private PaymentGatewayStub(String jwkSetJson) {
        this.jwkSetJson = Objects.requireNonNull(jwkSetJson, "jwkSetJson");
        this.server = HttpServer.create()
                .host("localhost")
                .port(0)
                .route(routes -> routes
                        .post("/v1/charges", this::charge)
                        .get("/v1/charges/{idempotencyKey}", (request, response) -> response.status(404).send())
                        .get("/jwks", (request, response) -> response.status(200)
                                .header("Content-Type", "application/jwk-set+json")
                                .sendString(Mono.just(this.jwkSetJson))))
                .bindNow();
    }

    /**
     * Starts the stub on a free port of {@code localhost}.
     *
     * @param jwkSetJson the JWK set served at {@code /jwks}
     * @return the running stub
     */
    public static PaymentGatewayStub start(String jwkSetJson) {
        return new PaymentGatewayStub(jwkSetJson);
    }

    /**
     * Base URL of the stub.
     *
     * @return {@code http://localhost:<port>}
     */
    public String baseUrl() {
        return "http://localhost:" + server.port();
    }

    /**
     * Charges received so far.
     *
     * @return an immutable snapshot
     */
    public List<RecordedCharge> charges() {
        return List.copyOf(charges);
    }

    @Override
    public void close() {
        server.disposeNow();
    }

    private Mono<Void> charge(HttpServerRequest request, HttpServerResponse response) {
        String idempotencyKey = request.requestHeaders().get("Idempotency-Key");
        String authorization = request.requestHeaders().get("Authorization");
        return request.receive().aggregate().asString().defaultIfEmpty("")
                .flatMap(body -> {
                    if (idempotencyKey == null || idempotencyKey.isBlank()) {
                        return json(response, 400, "{\"error\":\"MISSING_IDEMPOTENCY_KEY\"}");
                    }
                    String paymentToken = group(PAYMENT_TOKEN, body);
                    charges.add(new RecordedCharge(idempotencyKey, authorization, group(ORDER_ID, body),
                            paymentToken));
                    String result = paymentToken != null && paymentToken.startsWith(APPROVED_PREFIX)
                            ? "{\"paymentId\":\"pay_" + UUID.randomUUID() + "\",\"status\":\"APPROVED\","
                                    + "\"declineReason\":null}"
                            : "{\"paymentId\":null,\"status\":\"DECLINED\",\"declineReason\":\"INSUFFICIENT_FUNDS\"}";
                    return json(response, 201, result);
                });
    }

    private static Mono<Void> json(HttpServerResponse response, int status, String body) {
        return Mono.from(response.status(status).header("Content-Type", JSON).sendString(Mono.just(body)));
    }

    private static String group(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }
}
