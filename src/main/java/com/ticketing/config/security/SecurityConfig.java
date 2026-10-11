package com.ticketing.config.security;

import com.ticketing.config.properties.TicketingProperties;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.header.ReferrerPolicyServerHttpHeadersWriter.ReferrerPolicy;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter.Mode;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.util.Assert;

/**
 * Stateless OAuth2 resource server (JWT bearer tokens validated against the issuer's JWKS).
 *
 * <ul>
 *   <li>No session, CSRF, HTTP basic, form login or logout: every request carries its bearer token.</li>
 *   <li>Public: event reads ({@code GET /api/v1/events/**} except the ticket audit trail) and the health, info and
 *       Prometheus actuator endpoints.</li>
 *   <li>ADMIN: event creation, complimentary tickets and the ticket audit trail.</li>
 *   <li>CUSTOMER or ADMIN: orders (ownership is enforced by the use cases).</li>
 *   <li>Everything else requires authentication.</li>
 *   <li>Tokens must be signed by the JWKS, unexpired, issued by {@code ticketing.security.issuer}, addressed to
 *       {@code ticketing.security.audience} and carry a subject.</li>
 *   <li>Failures are rendered as problem details by {@link SecurityProblemHandler}; responses carry HSTS, no-sniff,
 *       frame-deny, no-referrer and a restrictive content security policy.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    static final String ROLE_ADMIN = "ADMIN";
    static final String ROLE_CUSTOMER = "CUSTOMER";
    static final Duration HSTS_MAX_AGE = Duration.ofDays(365);
    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'";

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
                        .hsts(hsts -> hsts.maxAge(HSTS_MAX_AGE).includeSubdomains(true))
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
     * JWT decoder backed by the issuer's JWK set (fetched lazily and cached), with the issuer, audience and subject
     * validators on top of the default timestamp validation. Replaces the auto-configured decoder.
     *
     * @param jwkSetUri  {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}
     * @param properties application settings ({@code ticketing.security.*})
     * @return the decoder
     */
    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            TicketingProperties properties) {
        Assert.hasText(jwkSetUri, "spring.security.oauth2.resourceserver.jwt.jwk-set-uri must be configured");
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(jwtValidator(properties.security()));
        return decoder;
    }

    /**
     * Token validation rules: not expired / not before (60 s clock skew), issuer, audience and a non-blank subject.
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
                new JwtAudienceValidator(security.audience()),
                // JwtClaimValidator rejects a missing claim itself; the predicate only sees present values.
                new JwtClaimValidator<String>(JwtClaimNames.SUB, subject -> !subject.isBlank()));
        return JwtValidators.createDefaultWithValidators(validators);
    }
}
