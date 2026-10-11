package com.ticketing.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.config.properties.TicketingProperties;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.test.StepVerifier;

class JwtRoleConverterTest {

    private static TicketingProperties properties(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("ticketing",
                TicketingProperties.class);
    }

    private static Jwt jwt(String claim, Object roles) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256").subject("customer-1")
                .issuedAt(Instant.parse("2026-10-10T10:00:00Z"));
        if (roles != null) {
            builder.claim(claim, roles);
        }
        return builder.build();
    }

    private static List<String> authorities(JwtRoleConverter converter, Jwt jwt) {
        return converter.authoritiesOf(jwt).stream().map(GrantedAuthority::getAuthority).toList();
    }

    private final JwtRoleConverter converter = new JwtRoleConverter(properties(Map.of()));

    @Test
    void shouldAuthenticateTheSubjectWithItsRoles() {
        Jwt jwt = jwt("roles", List.of("customer", "ROLE_ADMIN"));

        StepVerifier.create(converter.convert(jwt))
                .assertNext(authentication -> {
                    assertThat(authentication).isInstanceOf(JwtAuthenticationToken.class);
                    assertThat(authentication.getName()).isEqualTo("customer-1");
                    assertThat(authentication.isAuthenticated()).isTrue();
                    assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                            .containsExactly("ROLE_CUSTOMER", "ROLE_ADMIN");
                    assertThat(((JwtAuthenticationToken) authentication).getToken()).isSameAs(jwt);
                })
                .verifyComplete();
    }

    @Test
    void shouldSplitSpaceOrCommaSeparatedRoles() {
        assertThat(authorities(converter, jwt("roles", "customer admin,  auditor")))
                .containsExactly("ROLE_CUSTOMER", "ROLE_ADMIN", "ROLE_AUDITOR");
    }

    @Test
    void shouldIgnoreBlankNullAndDuplicateRoles() {
        assertThat(authorities(converter, jwt("roles", Arrays.asList("customer", null, " ", "CUSTOMER"))))
                .containsExactly("ROLE_CUSTOMER");
        assertThat(authorities(converter, jwt("roles", " , "))).isEmpty();
    }

    @Test
    void shouldGrantNoRoleWhenTheClaimIsMissingOrOfAnotherType() {
        assertThat(authorities(converter, jwt("roles", null))).isEmpty();
        assertThat(authorities(converter, jwt("roles", 42))).isEmpty();
    }

    @Test
    void shouldReadTheConfiguredClaim() {
        JwtRoleConverter cognito = new JwtRoleConverter(properties(Map.of(
                "ticketing.security.roles-claim", "cognito:groups")));

        assertThat(authorities(cognito, jwt("cognito:groups", List.of("admin")))).containsExactly("ROLE_ADMIN");
        assertThat(authorities(cognito, jwt("roles", List.of("admin")))).isEmpty();
    }

    @ParameterizedTest(name = "roles claim {0}")
    @ValueSource(strings = {"roles", "cognito:groups"})
    void shouldMapTheRolesOfTheConfiguredClaimOnly(String rolesClaim) {
        JwtRoleConverter configured = new JwtRoleConverter(properties(Map.of(
                "ticketing.security.roles-claim", rolesClaim)));
        String otherClaim = rolesClaim.equals("roles") ? "cognito:groups" : "roles";
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("customer-1")
                .issuedAt(Instant.parse("2026-10-10T10:00:00Z"))
                .claim(rolesClaim, List.of("CUSTOMER"))
                .claim(otherClaim, List.of("ADMIN"))
                .build();

        assertThat(authorities(configured, jwt)).containsExactly("ROLE_CUSTOMER");
    }
}
