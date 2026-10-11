package com.ticketing.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceOrClientIdValidatorTest {

    private static final String AUDIENCE = "ms-ticket";

    private final AudienceOrClientIdValidator validator = new AudienceOrClientIdValidator(AUDIENCE);

    private static Jwt token(Consumer<Jwt.Builder> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("customer-1")
                .issuedAt(Instant.parse("2026-10-10T10:00:00Z"));
        claims.accept(builder);
        return builder.build();
    }

    private OAuth2TokenValidatorResult validate(Consumer<Jwt.Builder> claims) {
        return validator.validate(token(claims));
    }

    @Test
    void shouldAcceptAnAudienceArrayContainingTheAudience() {
        assertThat(validate(jwt -> jwt.audience(List.of("other-api", AUDIENCE))).hasErrors()).isFalse();
    }

    @Test
    void shouldAcceptAnAudienceSentAsASingleJsonString() {
        // mock-oauth2-server serializes a single audience as a string, not an array
        assertThat(validate(jwt -> jwt.claim("aud", AUDIENCE)).hasErrors()).isFalse();
    }

    @Test
    void shouldAcceptCognitoAccessTokensWhoseClientIdIsTheAudience() {
        assertThat(validate(jwt -> jwt.claim("client_id", AUDIENCE).claim("token_use", "access")).hasErrors())
                .isFalse();
    }

    @Test
    void shouldAcceptAMatchingClientIdEvenWithAnotherAudience() {
        assertThat(validate(jwt -> jwt.audience(List.of("other-api")).claim("client_id", AUDIENCE)).hasErrors())
                .isFalse();
    }

    @Test
    void shouldRejectTokensForAnotherAudienceOrClient() {
        OAuth2TokenValidatorResult result = validate(jwt -> jwt.audience(List.of("other-api"))
                .claim("client_id", "another-client"));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement()
                .satisfies(error -> assertThat(error.getErrorCode()).isEqualTo(OAuth2ErrorCodes.INVALID_TOKEN));
    }

    @Test
    void shouldRejectTokensWithoutAudienceAndClientId() {
        assertThat(validate(jwt -> jwt.claim("scope", "orders.read")).hasErrors()).isTrue();
    }

    @Test
    void shouldIgnoreANonStringClientIdAndCompareCaseSensitively() {
        assertThat(validate(jwt -> jwt.claim("client_id", List.of(AUDIENCE))).hasErrors()).isTrue();
        assertThat(validate(jwt -> jwt.claim("client_id", "MS-TICKET")).hasErrors()).isTrue();
        assertThat(validate(jwt -> jwt.audience(List.of("MS-TICKET"))).hasErrors()).isTrue();
    }

    @Test
    void shouldRequireAnAudienceAndAToken() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AudienceOrClientIdValidator(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> new AudienceOrClientIdValidator(null));
        assertThatNullPointerException().isThrownBy(() -> validator.validate(null));
    }
}
