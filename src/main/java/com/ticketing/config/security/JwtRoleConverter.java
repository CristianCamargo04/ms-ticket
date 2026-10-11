package com.ticketing.config.security;

import com.ticketing.config.properties.TicketingProperties;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Turns a validated JWT into the authentication of the request: the principal name is the {@code sub} claim and the
 * authorities are {@code ROLE_<NAME>} for every role listed in the configured claim
 * ({@code ticketing.security.roles-claim}, {@code roles} by default; e.g. {@code cognito:groups} in AWS).
 *
 * <p>The claim may be a JSON array or a string separated by spaces or commas. Role names are trimmed, upper-cased and
 * an existing {@code ROLE_} prefix is not duplicated, so {@code ["customer", "ROLE_ADMIN"]} yields
 * {@code ROLE_CUSTOMER} and {@code ROLE_ADMIN}.
 */
@Component
public class JwtRoleConverter implements Converter<Jwt, Mono<AbstractAuthenticationToken>> {

    /** Prefix of the role authorities. */
    public static final String ROLE_PREFIX = "ROLE_";

    private final String rolesClaim;

    /**
     * Creates the converter.
     *
     * @param properties application settings ({@code ticketing.security.roles-claim})
     */
    public JwtRoleConverter(TicketingProperties properties) {
        this.rolesClaim = Objects.requireNonNull(properties.security().rolesClaim(), "rolesClaim");
    }

    @Override
    public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
        return Mono.just(new JwtAuthenticationToken(jwt, authoritiesOf(jwt), jwt.getSubject()));
    }

    /**
     * Extracts the role authorities of a token.
     *
     * @param jwt the token
     * @return distinct {@code ROLE_*} authorities (empty when the claim is missing or of an unsupported type)
     */
    List<GrantedAuthority> authoritiesOf(Jwt jwt) {
        Object claim = jwt.getClaims().get(rolesClaim);
        Stream<String> roleNames = switch (claim) {
            case Collection<?> values -> values.stream().filter(Objects::nonNull).map(Object::toString);
            case String value -> Arrays.stream(value.split("[\\s,]+"));
            case null, default -> Stream.empty();
        };
        return roleNames.map(String::strip)
                .filter(name -> !name.isEmpty())
                .map(JwtRoleConverter::toAuthority)
                .distinct()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
    }

    private static String toAuthority(String roleName) {
        String upperCase = roleName.toUpperCase(Locale.ROOT);
        return upperCase.startsWith(ROLE_PREFIX) ? upperCase : ROLE_PREFIX + upperCase;
    }
}
