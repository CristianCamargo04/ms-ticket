package com.ticketing.config.security;

import com.ticketing.config.web.ProblemDetailResponseWriter;
import com.ticketing.enums.ErrorCodeEnum;
import com.ticketing.presentation.impl.error.ProblemDetailFactory;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Renders the security failures of the filter chain as problem details: {@code 401 UNAUTHORIZED} (missing, invalid
 * or expired token) with an RFC 6750 {@code WWW-Authenticate} challenge, and {@code 403 FORBIDDEN} (authenticated
 * without the required role). Token validation details are never echoed to the client.
 */
@Component
public class SecurityProblemHandler implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    static final String UNAUTHORIZED_DETAIL = "A valid bearer token is required to access this resource";
    static final String FORBIDDEN_DETAIL = "The caller is not allowed to access this resource";
    static final String BEARER = "Bearer";
    static final String INSUFFICIENT_SCOPE_CHALLENGE = "Bearer error=\"insufficient_scope\"";

    /** RFC 6750 error codes are plain tokens; anything else is not echoed. */
    private static final Pattern ERROR_CODE = Pattern.compile("^[a-z_]{1,64}$");

    private final ProblemDetailFactory problems;
    private final ProblemDetailResponseWriter writer;

    /**
     * Creates the handler.
     *
     * @param problems builds the problem bodies
     * @param writer   writes them to the response
     */
    public SecurityProblemHandler(ProblemDetailFactory problems, ProblemDetailResponseWriter writer) {
        this.problems = Objects.requireNonNull(problems, "problems");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException exception) {
        return Mono.defer(() -> {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, bearerChallenge(exception));
            return writer.write(exchange,
                    problems.forErrorCode(ErrorCodeEnum.UNAUTHORIZED, UNAUTHORIZED_DETAIL, exchange));
        });
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return Mono.defer(() -> {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, INSUFFICIENT_SCOPE_CHALLENGE);
            return writer.write(exchange, problems.forErrorCode(ErrorCodeEnum.FORBIDDEN, FORBIDDEN_DETAIL, exchange));
        });
    }

    /**
     * RFC 6750 challenge: {@code Bearer} when no token was sent, {@code Bearer error="<code>"} when a token was
     * rejected (e.g. {@code invalid_token}).
     *
     * @param exception the authentication failure
     * @return the {@code WWW-Authenticate} value
     */
    static String bearerChallenge(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauth2Exception) {
            String errorCode = oauth2Exception.getError().getErrorCode();
            if (ERROR_CODE.matcher(errorCode).matches()) {
                return BEARER + " error=\"" + errorCode + "\"";
            }
        }
        return BEARER;
    }
}
