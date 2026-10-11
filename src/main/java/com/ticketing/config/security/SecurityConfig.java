package com.ticketing.config.security;

import com.ticketing.config.properties.TicketingProperties;
import io.netty.channel.ChannelOption;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy;
import org.springframework.security.web.server.header.ServerHttpHeadersWriter;
import org.springframework.security.web.server.header.StaticServerHttpHeadersWriter;
import org.springframework.security.web.server.header.StrictTransportSecurityServerHttpHeadersWriter;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter.Mode;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Stateless OAuth2 resource server (JWT bearer tokens validated against the issuer's JWKS).
 *
 * <ul>
 *   <li>No session, CSRF, HTTP basic, form login or logout: every request carries its bearer token.</li>
 *   <li>Public: event reads ({@code GET /api/v1/events/**} except the ticket audit trail) and the health, info and
 *       Prometheus actuator endpoints. This also holds when the actuator runs on its own management port
 *       ({@code management.server.port}, 8081 in AWS): the management child context builds its HTTP handler from the
 *       {@code WebFilter} beans of the whole context hierarchy, so this chain filters it as well (verified by
 *       {@code SecurityConfigManagementPortTest}).</li>
 *   <li>ADMIN: event creation, complimentary tickets and the ticket audit trail.</li>
 *   <li>CUSTOMER or ADMIN: orders (ownership is enforced by the use cases).</li>
 *   <li>Everything else requires authentication.</li>
 *   <li>Tokens must be RS256-signed by a key of the JWK set, unexpired, issued by {@code ticketing.security.issuer},
 *       addressed to {@code ticketing.security.audience} ({@code aud} contains it or {@code client_id} equals it, see
 *       {@link AudienceOrClientIdValidator}) and carry a subject. Roles come from the claim named by
 *       {@code ticketing.security.roles-claim} ({@link JwtRoleConverter}). The JWK set is downloaded with
 *       connect/response timeouts and re-downloaded for unknown key ids at most once per
 *       {@link #JWKS_MIN_REFRESH_INTERVAL} ({@link ThrottledJwkSource}).</li>
 *   <li>Failures are rendered as problem details by {@link SecurityProblemHandler}; responses carry HSTS, no-sniff,
 *       frame-deny, no-referrer and a restrictive content security policy. HSTS is written on HTTPS requests,
 *       including those whose TLS was terminated by the load balancer ({@code X-Forwarded-Proto: https}).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    static final String ROLE_ADMIN = "ADMIN";
    static final String ROLE_CUSTOMER = "CUSTOMER";
    static final Duration HSTS_MAX_AGE = Duration.ofDays(365);
    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'";

    /** Header set by a TLS-terminating proxy with the scheme the client used. */
    static final String FORWARDED_PROTO_HEADER = "X-Forwarded-Proto";

    /** Connect timeout of the JWK set download. */
    static final Duration JWKS_CONNECT_TIMEOUT = Duration.ofSeconds(1);

    /** Response timeout of the JWK set download. */
    static final Duration JWKS_RESPONSE_TIMEOUT = Duration.ofSeconds(2);

    /** Minimum time between two JWK set downloads triggered by tokens with an unknown key id. */
    static final Duration JWKS_MIN_REFRESH_INTERVAL = Duration.ofSeconds(30);

    private static final String HTTPS = "https";
    private static final MediaType JWK_SET_MEDIA_TYPE = MediaType.parseMediaType("application/jwk-set+json");

    /**
     * The security filter chain.
     *
     * @param http             the reactive security builder
     * @param jwtRoleConverter maps token roles to authorities
     * @param problemHandler   renders 401/403 problems
     * @return the filter chain
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, JwtRoleConverter jwtRoleConverter,
                                                         SecurityProblemHandler problemHandler) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .headers(headers -> headers
                        .hsts(ServerHttpSecurity.HeaderSpec.HstsSpec::disable)
                        .writer(strictTransportSecurityWriter())
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frameOptions -> frameOptions.mode(Mode.DENY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY)))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus").permitAll()
                        .pathMatchers("/api/v1/events/*/tickets/**").hasRole(ROLE_ADMIN)
                        .pathMatchers(HttpMethod.GET, "/api/v1/events", "/api/v1/events/**").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/events").hasRole(ROLE_ADMIN)
                        .pathMatchers(HttpMethod.POST, "/api/v1/events/*/complimentary-tickets").hasRole(ROLE_ADMIN)
                        .pathMatchers(HttpMethod.POST, "/api/v1/events/*/orders")
                        .hasAnyRole(ROLE_CUSTOMER, ROLE_ADMIN)
                        .pathMatchers("/api/v1/orders/**").hasAnyRole(ROLE_CUSTOMER, ROLE_ADMIN)
                        .anyExchange().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtRoleConverter))
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                .build();
    }

    /**
     * {@code Strict-Transport-Security} writer. Spring Security's own writer only considers the scheme of the request
     * the application received, which is {@code http} behind a TLS-terminating load balancer, so the header would
     * never be sent in production. This writer also honours {@code X-Forwarded-Proto: https}. A spoofed header only
     * affects the response of the spoofing client, and browsers ignore HSTS received over plain HTTP (RFC 6797,
     * section 8.1), so the header is not trusted for anything else (the request URI is left untouched).
     *
     * @return the writer
     */
    static ServerHttpHeadersWriter strictTransportSecurityWriter() {
        ServerHttpHeadersWriter hsts = StaticServerHttpHeadersWriter.builder()
                .header(StrictTransportSecurityServerHttpHeadersWriter.STRICT_TRANSPORT_SECURITY,
                        "max-age=" + HSTS_MAX_AGE.toSeconds() + " ; includeSubDomains")
                .build();
        return exchange -> isHttps(exchange.getRequest()) ? hsts.writeHttpHeaders(exchange) : Mono.empty();
    }

    /**
     * Whether the client reached the service over HTTPS: the request scheme, else the first (client-facing) value of
     * {@code X-Forwarded-Proto}.
     *
     * @param request the request
     * @return {@code true} for HTTPS
     */
    static boolean isHttps(ServerHttpRequest request) {
        if (HTTPS.equalsIgnoreCase(request.getURI().getScheme())) {
            return true;
        }
        String forwardedProto = request.getHeaders().getFirst(FORWARDED_PROTO_HEADER);
        return forwardedProto != null && HTTPS.equalsIgnoreCase(forwardedProto.split(",", 2)[0].strip());
    }

    /**
     * JWT decoder verifying RS256 signatures with the issuer's JWK set ({@link ThrottledJwkSource}: downloaded
     * lazily with timeouts, refreshes for unknown key ids throttled), with the issuer, audience and subject
     * validators on top of the default timestamp validation. Replaces the auto-configured decoder.
     *
     * @param jwkSetUri  {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}
     * @param properties application settings ({@code ticketing.security.*})
     * @param clock      time source of the refresh throttle
     * @return the decoder
     */
    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            TicketingProperties properties, Clock clock) {
        Assert.hasText(jwkSetUri, "spring.security.oauth2.resourceserver.jwt.jwk-set-uri must be configured");
        ThrottledJwkSource keys = new ThrottledJwkSource(
                jwkSetDocument(jwkSetUri, JWKS_CONNECT_TIMEOUT, JWKS_RESPONSE_TIMEOUT), clock,
                JWKS_MIN_REFRESH_INTERVAL);
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSource(keys)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(jwtValidator(properties.security()));
        return decoder;
    }

    /**
     * Cold download of the JWK set document over a dedicated client with connect and response timeouts, so a slow
     * or unreachable issuer fails fast instead of stalling every authenticated request.
     *
     * @param jwkSetUri       the JWK set endpoint
     * @param connectTimeout  TCP connect timeout
     * @param responseTimeout time allowed for the response
     * @return one GET per subscription; errors on non-2xx statuses and timeouts
     */
    static Mono<String> jwkSetDocument(String jwkSetUri, Duration connectTimeout, Duration responseTimeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()))
                .responseTimeout(responseTimeout);
        WebClient webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        return webClient.get().uri(jwkSetUri)
                .accept(MediaType.APPLICATION_JSON, JWK_SET_MEDIA_TYPE)
                .retrieve()
                .bodyToMono(String.class);
    }

    /**
     * Token validation rules: not expired / not before (60 s clock skew), issuer, audience ({@code aud} or
     * {@code client_id}) and a non-blank subject.
     *
     * @param security {@code ticketing.security.*}
     * @return the composite validator
     * @throws IllegalArgumentException when the issuer or the audience is not configured
     */
    static OAuth2TokenValidator<Jwt> jwtValidator(TicketingProperties.Security security) {
        Assert.hasText(security.issuer(), "ticketing.security.issuer must be configured");
        Assert.hasText(security.audience(), "ticketing.security.audience must be configured");
        List<OAuth2TokenValidator<Jwt>> validators = List.of(
                new JwtIssuerValidator(security.issuer()),
                new AudienceOrClientIdValidator(security.audience()),
                // JwtClaimValidator rejects a missing claim itself; the predicate only sees present values.
                new JwtClaimValidator<String>(JwtClaimNames.SUB, subject -> !subject.isBlank()));
        return JwtValidators.createDefaultWithValidators(validators);
    }
}
