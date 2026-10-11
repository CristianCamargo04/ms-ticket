package com.ticketing.config.security;

import java.util.Objects;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.util.Assert;

/**
 * Accepts a token addressed to this API: its {@code aud} claim contains the configured audience, or its
 * {@code client_id} claim equals it.
 *
 * <p>Both shapes exist in practice:
 * <ul>
 *   <li>Standard access tokens and OIDC ID tokens carry {@code aud}, either as a JSON array or as a single JSON
 *       string (mock-oauth2-server sends a string); both are read as a list.</li>
 *   <li>Amazon Cognito <em>access</em> tokens have no {@code aud} claim: they carry the app client in
 *       {@code client_id}. The infrastructure injects that client id as {@code ticketing.security.audience}, so tokens
 *       minted for any other app client of the user pool are rejected.</li>
 * </ul>
 * A token matching neither claim fails with {@code invalid_token}. Issuer, signature and expiry are checked by the
 * other validators of the decoder.
 */
public final class AudienceOrClientIdValidator implements OAuth2TokenValidator<Jwt> {

    /** Claim of the OAuth2 client the token was issued to (RFC 9068, Cognito access tokens). */
    public static final String CLIENT_ID_CLAIM = "client_id";

    private final String audience;
    private final OAuth2Error error;

    /**
     * Creates the validator.
     *
     * @param audience the accepted audience / client id
     * @throws IllegalArgumentException when the audience is blank
     */
    public AudienceOrClientIdValidator(String audience) {
        Assert.hasText(audience, "audience must not be blank");
        this.audience = audience;
        this.error = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
                "The token is not addressed to this API (aud / client_id)", null);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Objects.requireNonNull(token, "token");
        return hasAudience(token) || audience.equals(clientIdOf(token))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(error);
    }

    private boolean hasAudience(Jwt token) {
        // a single string is converted to a one-element list
        return token.hasClaim(JwtClaimNames.AUD) && token.getAudience().contains(audience);
    }

    private static String clientIdOf(Jwt token) {
        Object clientId = token.getClaims().get(CLIENT_ID_CLAIM);
        return clientId instanceof String value ? value : null;
    }
}
