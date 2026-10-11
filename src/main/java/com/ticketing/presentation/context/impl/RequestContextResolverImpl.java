package com.ticketing.presentation.context.impl;

import com.ticketing.presentation.context.RequestContext;
import com.ticketing.presentation.context.RequestContextResolver;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Set;
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

/**
 * Resolves the caller from the Reactor security context (JWT {@code sub} and {@code ROLE_*} authorities) and the
 * request headers (correlation id, client IP).
 */
@Component
public class RequestContextResolverImpl implements RequestContextResolver {

    /** Header listing the client and the proxies a request went through ({@code client, proxy1, proxy2}). */
    public static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private static final String ROLE_PREFIX = "ROLE_";

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

    /**
     * Resolves the client IP: the first hop of {@code X-Forwarded-For} (set by the load balancer), else the remote
     * address of the connection.
     *
     * @param request the request
     * @return the client IP, {@code null} when unknown
     */
    public static String clientIpOf(ServerHttpRequest request) {
        String forwardedFor = request.getHeaders().getFirst(FORWARDED_FOR_HEADER);
        if (forwardedFor != null) {
            String firstHop = forwardedFor.split(",", 2)[0].strip();
            if (!firstHop.isEmpty()) {
                return firstHop;
            }
        }
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress == null) {
            return null;
        }
        return remoteAddress.getAddress() != null
                ? remoteAddress.getAddress().getHostAddress()
                : remoteAddress.getHostString();
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
