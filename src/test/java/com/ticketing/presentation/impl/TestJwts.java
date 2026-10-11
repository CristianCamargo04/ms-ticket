package com.ticketing.presentation.impl;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import java.time.Instant;
import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;

public final class TestJwts {
    public static final String CUSTOMER = "customer-1";

    public static final String ADMIN = "admin-1";

    private TestJwts() {
    }

    public static JwtMutator customer(String subject) {
        return mockJwt().jwt(jwt -> jwt.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    public static JwtMutator admin(String subject) {
        return mockJwt().jwt(jwt -> jwt.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

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
