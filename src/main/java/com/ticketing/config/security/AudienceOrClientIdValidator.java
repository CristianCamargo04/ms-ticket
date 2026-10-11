package com.ticketing.config.security;

import java.util.Objects;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.util.Assert;

public final class AudienceOrClientIdValidator implements OAuth2TokenValidator<Jwt> {
    public static final String CLIENT_ID_CLAIM = "client_id";

    private final String audience;
    private final OAuth2Error error;

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
        return token.hasClaim(JwtClaimNames.AUD) && token.getAudience().contains(audience);
    }

    private static String clientIdOf(Jwt token) {
        Object clientId = token.getClaims().get(CLIENT_ID_CLAIM);
        return clientId instanceof String value ? value : null;
    }
}
