package com.ticketing.presentation.context.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ticketing.presentation.context.RequestContext;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RequestContextResolverImplTest {
    private final RequestContextResolverImpl resolver = new RequestContextResolverImpl();

    private static Jwt jwt(String subject) {
        return Jwt.withTokenValue("token").header("alg", "none").subject(subject)
                .issuedAt(Instant.parse("2026-10-10T10:00:00Z")).build();
    }

    private static Mono<RequestContext> resolveAs(Authentication authentication, MockServerHttpRequest request) {
        return new RequestContextResolverImpl().resolve(request)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }

    @Test
    void shouldResolveTheCallerFromTheJwt() {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt("customer-1"),
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"), new SimpleGrantedAuthority("SCOPE_orders")),
                "ignored-name");
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/orders/o-1")
                .header(RequestContext.CORRELATION_ID_HEADER, "corr-1")
                .header(RequestContextResolverImpl.FORWARDED_FOR_HEADER, "198.51.100.66, 203.0.113.7")
                .build();

        StepVerifier.create(resolveAs(authentication, request))
                .assertNext(context -> {
                    assertThat(context.subject()).isEqualTo("customer-1");
                    assertThat(context.roles()).containsExactly("CUSTOMER");
                    assertThat(context.isAdmin()).isFalse();
                    assertThat(context.correlationId()).isEqualTo("corr-1");
                    assertThat(context.clientIp()).isEqualTo("203.0.113.7");
                })
                .verifyComplete();
    }

    @Test
    void shouldUseThePrincipalNameOfNonJwtAuthentications() {
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated("admin-1", null,
                AuthorityUtils.createAuthorityList("ROLE_ADMIN"));

        StepVerifier.create(resolveAs(authentication, MockServerHttpRequest.get("/").build()))
                .assertNext(context -> {
                    assertThat(context.subject()).isEqualTo("admin-1");
                    assertThat(context.isAdmin()).isTrue();
                    assertThat(context.correlationId()).isNull();
                    assertThat(context.clientIp()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void shouldFailWithoutSecurityContext() {
        StepVerifier.create(resolver.resolve(MockServerHttpRequest.get("/").build()))
                .verifyError(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void shouldFailWithoutAuthentication() {
        Mono<RequestContext> context = resolver.resolve(MockServerHttpRequest.get("/").build())
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(new SecurityContextImpl())));

        StepVerifier.create(context).verifyError(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void shouldRejectUnauthenticatedAndAnonymousCallers() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/").build();

        StepVerifier.create(resolveAs(UsernamePasswordAuthenticationToken.unauthenticated("user", "pwd"), request))
                .verifyError(AuthenticationCredentialsNotFoundException.class);
        StepVerifier.create(resolveAs(new AnonymousAuthenticationToken("key", "anonymous",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")), request))
                .verifyError(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void shouldRejectTokensWithoutSubject() {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt(" "), List.of());

        StepVerifier.create(resolveAs(authentication, MockServerHttpRequest.get("/").build()))
                .verifyError(AuthenticationCredentialsNotFoundException.class);
        Jwt withoutSubject = Jwt.withTokenValue("token").header("alg", "none").claim("scope", "x").build();
        StepVerifier.create(resolveAs(new JwtAuthenticationToken(withoutSubject, List.of()),
                        MockServerHttpRequest.get("/").build()))
                .verifyError(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void shouldRequireTheRequest() {
        assertThatNullPointerException().isThrownBy(() -> resolver.resolve(null));
    }

    private static InetSocketAddress loadBalancer() throws Exception {
        return new InetSocketAddress(InetAddress.getByAddress(new byte[] {10, 0, 0, 2}), 4321);
    }

    private static String clientIpWithForwardedFor(String... headerLines) throws Exception {
        return RequestContextResolverImpl.clientIpOf(MockServerHttpRequest.get("/")
                .header(RequestContextResolverImpl.FORWARDED_FOR_HEADER, headerLines)
                .remoteAddress(loadBalancer())
                .build());
    }

    @Test
    void shouldTakeTheRightmostForwardedForHopAppendedByTheLoadBalancer() throws Exception {
        assertThat(clientIpWithForwardedFor("203.0.113.7")).isEqualTo("203.0.113.7");

        assertThat(clientIpWithForwardedFor("1.2.3.4, 203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(clientIpWithForwardedFor("1.2.3.4,203.0.113.7 , ")).isEqualTo("203.0.113.7");
        assertThat(clientIpWithForwardedFor("2001:db8::1")).isEqualTo("2001:db8::1");
        assertThat(clientIpWithForwardedFor("::ffff:203.0.113.7")).isEqualTo("::ffff:203.0.113.7");
    }

    @Test
    void shouldReadRepeatedForwardedForHeadersAsOneList() throws Exception {
        assertThat(clientIpWithForwardedFor("1.2.3.4", "5.6.7.8, 203.0.113.9")).isEqualTo("203.0.113.9");
        assertThat(clientIpWithForwardedFor("1.2.3.4, 203.0.113.9", " ")).isEqualTo("203.0.113.9");
    }

    @Test
    void shouldFallBackToTheRemoteAddressWhenForwardedForIsBlank() throws Exception {
        assertThat(clientIpWithForwardedFor(" , ")).isEqualTo("10.0.0.2");
        assertThat(clientIpWithForwardedFor("")).isEqualTo("10.0.0.2");
    }

    @Test
    void shouldIgnoreARightmostHopThatIsNotAnIpAddress() throws Exception {
        assertThat(clientIpWithForwardedFor("203.0.113.7, evil\nINFO forged log line")).isEqualTo("10.0.0.2");
        assertThat(clientIpWithForwardedFor("unknown")).isEqualTo("10.0.0.2");
        assertThat(clientIpWithForwardedFor("1".repeat(46))).isEqualTo("10.0.0.2");
        assertThat(clientIpWithForwardedFor("1")).isEqualTo("10.0.0.2");
    }

    @Test
    void shouldUseTheRemoteAddressWithoutForwardedFor() throws Exception {
        assertThat(RequestContextResolverImpl.clientIpOf(MockServerHttpRequest.get("/")
                .remoteAddress(loadBalancer()).build())).isEqualTo("10.0.0.2");
    }

    @Test
    void shouldUseTheHostOfUnresolvedRemoteAddresses() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/")
                .remoteAddress(InetSocketAddress.createUnresolved("client.example", 4321))
                .build();

        assertThat(RequestContextResolverImpl.clientIpOf(request)).isEqualTo("client.example");
    }

    @Test
    void shouldReturnNullWhenTheClientIsUnknown() {
        assertThat(RequestContextResolverImpl.clientIpOf(MockServerHttpRequest.get("/").build())).isNull();
    }
}
