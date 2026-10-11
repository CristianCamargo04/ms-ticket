package com.ticketing.presentation.context;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record RequestContext(String subject, Set<String> roles, String correlationId, String clientIp) {
    public static final String ROLE_ADMIN = "ADMIN";

    public static final String ROLE_CUSTOMER = "CUSTOMER";

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    public static final String CORRELATION_ID_KEY = "correlationId";

    private static final String AUTHORITY_PREFIX = "ROLE_";

    public RequestContext {
        Objects.requireNonNull(subject, "subject");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        roles = roles == null
                ? Set.of()
                : roles.stream().map(RequestContext::normalizeRole).collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAdmin() {
        return hasRole(ROLE_ADMIN);
    }

    public boolean hasRole(String role) {
        return roles.contains(normalizeRole(Objects.requireNonNull(role, "role")));
    }

    private static String normalizeRole(String role) {
        return role.startsWith(AUTHORITY_PREFIX) ? role.substring(AUTHORITY_PREFIX.length()) : role;
    }
}
