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

/**
 * An in-test OAuth2 issuer: one RSA signing key, its public JWK set (served by {@link PaymentGatewayStub} at
 * {@code /jwks}) and RS256 tokens shaped like the ones the application receives in AWS.
 *
 * <ul>
 *   <li>Customer tokens look like <b>Cognito access tokens</b>: no {@code aud}, the app client in {@code client_id},
 *       the groups in {@code cognito:groups}.</li>
 *   <li>Admin tokens carry {@code aud} as a <b>single JSON string</b> (like mock-oauth2-server).</li>
 * </ul>
 * The application therefore runs with {@code ticketing.security.roles-claim=cognito:groups} and validates both
 * audience shapes with the real decoder (signature, issuer, audience, expiry).
 */
public final class TestJwtIssuer {

    /** Value of the {@code iss} claim. */
    public static final String ISSUER = "https://issuer.e2e.test/ms-ticket";

    /** Audience / app client id accepted by the application. */
    public static final String AUDIENCE = "ms-ticket-e2e-client";

    /** Claim holding the groups (Cognito). */
    public static final String ROLES_CLAIM = "cognito:groups";

    private static final Duration LIFETIME = Duration.ofHours(1);

    private final RSAKey key;

    private TestJwtIssuer(RSAKey key) {
        this.key = key;
    }

    /**
     * Generates a fresh signing key.
     *
     * @return the issuer
     */
    public static TestJwtIssuer create() {
        try {
            return new TestJwtIssuer(new RSAKeyGenerator(2048).keyID("e2e-" + UUID.randomUUID()).generate());
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot generate the test signing key", e);
        }
    }

    /**
     * The public JWK set document.
     *
     * @return the JSON of the public keys
     */
    public String jwkSetJson() {
        return new JWKSet(key).toPublicJWKSet().toString();
    }

    /**
     * A Cognito-style access token of a customer.
     *
     * @param subject the customer id ({@code sub})
     * @return the serialized token
     */
    public String customerToken(String subject) {
        return sign(new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("client_id", AUDIENCE)
                .claim("token_use", "access")
                .claim(ROLES_CLAIM, List.of("CUSTOMER")));
    }

    /**
     * An administrator token whose audience is a single string.
     *
     * @param subject the administrator id ({@code sub})
     * @return the serialized token
     */
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
