package com.ticketing.presentation.context;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Caller context of an authenticated request.
 *
 * <p>Roles are stored without the Spring Security {@code ROLE_} prefix: both {@code "ADMIN"} and
 * {@code "ROLE_ADMIN"} are accepted and normalized to {@code "ADMIN"}.
 *
 * @param subject       JWT subject (customer or administrator id), non-blank
 * @param roles         normalized role names (immutable, never {@code null})
 * @param correlationId correlation id of the request, nullable
 * @param clientIp      client IP address, nullable when unknown
 */
public record RequestContext(String subject, Set<String> roles, String correlationId, String clientIp) {

    /** Administrator role name. */
    public static final String ROLE_ADMIN = "ADMIN";

    /** Customer role name. */
    public static final String ROLE_CUSTOMER = "CUSTOMER";

    /** HTTP header carrying the correlation id (set or echoed by {@code CorrelationIdWebFilter}). */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /** Exchange attribute and Reactor context key holding the correlation id. */
    public static final String CORRELATION_ID_KEY = "correlationId";

    private static final String AUTHORITY_PREFIX = "ROLE_";

    /**
     * Validates the subject and normalizes the roles ({@code null} means no role).
     */
    public RequestContext {
        Objects.requireNonNull(subject, "subject");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        roles = roles == null
                ? Set.of()
                : roles.stream().map(RequestContext::normalizeRole).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Whether the caller is an administrator.
     *
     * @return {@code true} when the roles contain {@link #ROLE_ADMIN}
     */
    public boolean isAdmin() {
        return hasRole(ROLE_ADMIN);
    }

    /**
     * Whether the caller has a role.
     *
     * @param role role name, with or without the {@code ROLE_} prefix
     * @return {@code true} when the caller has the role
     */
    public boolean hasRole(String role) {
        return roles.contains(normalizeRole(Objects.requireNonNull(role, "role")));
    }

    private static String normalizeRole(String role) {
        return role.startsWith(AUTHORITY_PREFIX) ? role.substring(AUTHORITY_PREFIX.length()) : role;
    }
}
