package com.ticketing.presentation.context.impl;

import com.ticketing.presentation.context.RequestContext;
import com.ticketing.presentation.context.RequestContextResolver;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class RequestContextResolverImpl implements RequestContextResolver {
    public static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private static final String ROLE_PREFIX = "ROLE_";

    private static final Pattern IP_ADDRESS = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    @Override
    public Mono<RequestContext> resolve(ServerHttpRequest request) {
        Objects.requireNonNull(request, "request");
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .filter(RequestContextResolverImpl::isAuthenticated)
                .mapNotNull(authentication -> toContext(authentication, request))
                .switchIfEmpty(Mono.error(() ->
                        new AuthenticationCredentialsNotFoundException("The request is not authenticated")));
    }

    public static String clientIpOf(ServerHttpRequest request) {
        String lastHop = lastForwardedHop(request.getHeaders().get(FORWARDED_FOR_HEADER));
        if (lastHop != null && IP_ADDRESS.matcher(lastHop).matches()) {
            return lastHop;
        }
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress == null) {
            return null;
        }
        return remoteAddress.getAddress() != null
                ? remoteAddress.getAddress().getHostAddress()
                : remoteAddress.getHostString();
    }

    private static String lastForwardedHop(List<String> headerLines) {
        if (headerLines == null) {
            return null;
        }
        for (int line = headerLines.size() - 1; line >= 0; line--) {
            String[] hops = headerLines.get(line).split(",");
            for (int hop = hops.length - 1; hop >= 0; hop--) {
                String candidate = hops[hop].strip();
                if (!candidate.isEmpty()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private static RequestContext toContext(Authentication authentication, ServerHttpRequest request) {
        String subject = subjectOf(authentication);
        if (subject == null || subject.isBlank()) {
            return null;
        }
        return new RequestContext(subject, rolesOf(authentication),
                request.getHeaders().getFirst(RequestContext.CORRELATION_ID_HEADER), clientIpOf(request));
    }

    private static String subjectOf(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken jwtAuthentication
                ? jwtAuthentication.getToken().getSubject()
                : authentication.getName();
    }

    private static Set<String> rolesOf(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .collect(Collectors.toUnmodifiableSet());
    }
}
