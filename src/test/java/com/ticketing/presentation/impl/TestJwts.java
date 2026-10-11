package com.ticketing.presentation.impl;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import java.time.Instant;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;

/**
 * Authenticated callers for the WebFlux slice tests.
 */
public final class TestJwts {

    /** Subject of the default customer. */
    public static final String CUSTOMER = "customer-1";

    /** Subject of the default administrator. */
    public static final String ADMIN = "admin-1";

    private TestJwts() {
    }

    /**
     * A customer authenticated through a mocked JWT ({@code ROLE_CUSTOMER}).
     *
     * @param subject the JWT subject
     * @return the mutator to apply to the web test client
     */
    public static JwtMutator customer(String subject) {
        return mockJwt().jwt(jwt -> jwt.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    /**
     * An administrator authenticated through a mocked JWT ({@code ROLE_ADMIN}).
     *
     * @param subject the JWT subject
     * @return the mutator to apply to the web test client
     */
    public static JwtMutator admin(String subject) {
        return mockJwt().jwt(jwt -> jwt.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /**
     * A decoded token as the mocked {@code ReactiveJwtDecoder} would return it, so the real role converter runs.
     *
     * @param subject the subject
     * @param roles   the {@code roles} claim
     * @return the token
     */
    public static Jwt decoded(String subject, String... roles) {
        return Jwt.withTokenValue("token-" + subject)
                .header("alg", "RS256")
                .subject(subject)
                .claim("roles", List.of(roles))
                .issuedAt(Instant.parse("2026-10-10T09:59:00Z"))
                .expiresAt(Instant.parse("2026-10-10T11:00:00Z"))
                .build();
    }
}
