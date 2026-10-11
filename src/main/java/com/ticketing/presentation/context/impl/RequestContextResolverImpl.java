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

/**
 * Resolves the caller from the Reactor security context (JWT {@code sub} and {@code ROLE_*} authorities) and the
 * request headers (correlation id, client IP: rightmost {@code X-Forwarded-For} hop, see {@link #clientIpOf}).
 */
@Component
public class RequestContextResolverImpl implements RequestContextResolver {

    /**
     * Header listing the client and the proxies a request went through ({@code client, proxy1, proxy2}); each proxy
     * appends the address of its peer.
     */
    public static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private static final String ROLE_PREFIX = "ROLE_";

    /** Characters of an IPv4 or IPv6 address (including an IPv4-mapped suffix), at most 45 of them. */
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

    /**
     * Resolves the client IP: the <em>rightmost</em> hop of {@code X-Forwarded-For}, else the remote address of the
     * connection (the rule of the {@code RequestContextResolver} contract).
     *
     * <p>The service runs behind exactly one trusted proxy, the AWS Application Load Balancer, which <em>appends</em>
     * the address of its TCP peer to {@code X-Forwarded-For}. Every hop to the left of that one was sent by the client
     * and can be forged, so the first hop must never be trusted; the rightmost hop is the address the ALB saw. When
     * the header is repeated, its lines are read as one comma-separated list. A rightmost hop that is not shaped like
     * an IPv4/IPv6 address (letters other than hex digits, spaces, control characters: log-injection material) is
     * ignored and the remote address is used instead.
     *
     * <p>The remote address cannot be used behind the ALB: with {@code server.forward-headers-strategy=native}
     * (production) Reactor Netty replaces it with the <em>first</em> {@code X-Forwarded-For} hop, which is the
     * client-controlled one. The value feeds logs and the fallback rate-limit key of unauthenticated callers; it is
     * never used for an authorization decision.
     *
     * @param request the request
     * @return the client IP, {@code null} when unknown
     */
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

    /**
     * The rightmost non-blank hop of the {@code X-Forwarded-For} header lines.
     *
     * @param headerLines the header values, {@code null} when absent
     * @return the trimmed hop, {@code null} when there is none
     */
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
