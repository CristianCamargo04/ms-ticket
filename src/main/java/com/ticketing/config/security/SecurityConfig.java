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

@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {
    static final String ROLE_ADMIN = "ADMIN";
    static final String ROLE_CUSTOMER = "CUSTOMER";
    static final Duration HSTS_MAX_AGE = Duration.ofDays(365);
    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'";

    static final String FORWARDED_PROTO_HEADER = "X-Forwarded-Proto";

    static final Duration JWKS_CONNECT_TIMEOUT = Duration.ofSeconds(1);

    static final Duration JWKS_RESPONSE_TIMEOUT = Duration.ofSeconds(2);

    static final Duration JWKS_MIN_REFRESH_INTERVAL = Duration.ofSeconds(30);

    private static final String HTTPS = "https";
    private static final MediaType JWK_SET_MEDIA_TYPE = MediaType.parseMediaType("application/jwk-set+json");

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

    static ServerHttpHeadersWriter strictTransportSecurityWriter() {
        ServerHttpHeadersWriter hsts = StaticServerHttpHeadersWriter.builder()
                .header(StrictTransportSecurityServerHttpHeadersWriter.STRICT_TRANSPORT_SECURITY,
                        "max-age=" + HSTS_MAX_AGE.toSeconds() + " ; includeSubDomains")
                .build();
        return exchange -> isHttps(exchange.getRequest()) ? hsts.writeHttpHeaders(exchange) : Mono.empty();
    }

    static boolean isHttps(ServerHttpRequest request) {
        if (HTTPS.equalsIgnoreCase(request.getURI().getScheme())) {
            return true;
        }
        String forwardedProto = request.getHeaders().getFirst(FORWARDED_PROTO_HEADER);
        return forwardedProto != null && HTTPS.equalsIgnoreCase(forwardedProto.split(",", 2)[0].strip());
    }

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

    static OAuth2TokenValidator<Jwt> jwtValidator(TicketingProperties.Security security) {
        Assert.hasText(security.issuer(), "ticketing.security.issuer must be configured");
        Assert.hasText(security.audience(), "ticketing.security.audience must be configured");
        List<OAuth2TokenValidator<Jwt>> validators = List.of(
                new JwtIssuerValidator(security.issuer()),
                new AudienceOrClientIdValidator(security.audience()),

                new JwtClaimValidator<String>(JwtClaimNames.SUB, subject -> !subject.isBlank()));
        return JwtValidators.createDefaultWithValidators(validators);
    }
}
