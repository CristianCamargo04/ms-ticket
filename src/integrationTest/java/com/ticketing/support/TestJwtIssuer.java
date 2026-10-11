package com.ticketing.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public final class TestJwtIssuer {
    public static final String ISSUER = "https://issuer.e2e.test/ms-ticket";

    public static final String AUDIENCE = "ms-ticket-e2e-client";

    public static final String ROLES_CLAIM = "cognito:groups";

    private static final Duration LIFETIME = Duration.ofHours(1);

    private final RSAKey key;

    private TestJwtIssuer(RSAKey key) {
        this.key = key;
    }

    public static TestJwtIssuer create() {
        try {
            return new TestJwtIssuer(new RSAKeyGenerator(2048).keyID("e2e-" + UUID.randomUUID()).generate());
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot generate the test signing key", e);
        }
    }

    public String jwkSetJson() {
        return new JWKSet(key).toPublicJWKSet().toString();
    }

    public String customerToken(String subject) {
        return sign(new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("client_id", AUDIENCE)
                .claim("token_use", "access")
                .claim(ROLES_CLAIM, List.of("CUSTOMER")));
    }

    public String adminToken(String subject) {
        return sign(new JWTClaimsSet.Builder()
                .subject(subject)
                .audience(AUDIENCE)
                .claim(ROLES_CLAIM, List.of("ADMIN")));
    }

    private String sign(JWTClaimsSet.Builder claims) {
        Instant now = Instant.now();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                claims.issuer(ISSUER)
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plus(LIFETIME)))
                        .jwtID(UUID.randomUUID().toString())
                        .build());
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign the test token", e);
        }
        return jwt.serialize();
    }
}
