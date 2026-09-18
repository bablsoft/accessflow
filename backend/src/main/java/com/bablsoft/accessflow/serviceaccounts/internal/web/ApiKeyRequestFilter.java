package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.security.api.ApiKeyAuthentication;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountRateLimitExceededException;
import com.bablsoft.accessflow.serviceaccounts.internal.ServiceAccountRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;

/**
 * Applies the per-identity rate limit to every API-key-authenticated request (#873) — {@code /mcp/**}
 * and {@code /api/v1/**} alike, never a JWT browser session. Registered by
 * {@code ServiceAccountsConfiguration} as a servlet filter at order {@code 0}: Spring Security's
 * {@code springSecurityFilterChain} sits at {@code -100}, so this runs <i>inside</i> it with the
 * {@code SecurityContext} populated and not yet cleared, after the authorization filter has already
 * committed any 401/403. Deliberately not a {@code @Component} (Boot would register it a second time)
 * and deliberately not added to {@code SecurityConfiguration} — that would need
 * {@code security → serviceaccounts}, a module cycle.
 *
 * <p>The filter is outside {@code @ControllerAdvice} reach, so it writes the RFC 9457
 * {@code ProblemDetail} itself, the way {@code SecurityExceptionHandler} does, resolving the detail
 * through {@link MessageSource} with {@code request.getLocale()}.
 */
@RequiredArgsConstructor
public class ApiKeyRequestFilter extends OncePerRequestFilter {

    static final String ERROR_CODE = "SERVICE_ACCOUNT_RATE_LIMIT_EXCEEDED";
    /** Suffixed with the exhausted window ({@code minute} / {@code day}) so the unit is translated. */
    static final String MESSAGE_KEY_PREFIX = "error.service_account_rate_limit_exceeded.";

    private final ServiceAccountRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;
    private final Clock clock;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof ApiKeyAuthentication
                && authentication.getPrincipal() instanceof JwtClaims claims) {
            try {
                rateLimiter.enforce(claims.userId());
            } catch (ServiceAccountRateLimitExceededException ex) {
                writeTooManyRequests(request, response, ex);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response,
                                      ServiceAccountRateLimitExceededException ex) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
        var detail = messageSource.getMessage(MESSAGE_KEY_PREFIX + ex.window(), new Object[] {ex.limit()},
                request.getLocale());
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, detail);
        pd.setProperty("error", ERROR_CODE);
        pd.setProperty("limit", ex.limit());
        pd.setProperty("retryAfterSeconds", ex.retryAfterSeconds());
        pd.setProperty("timestamp", clock.instant().toString());
        var traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            pd.setProperty("traceId", traceId);
        }
        objectMapper.writeValue(response.getOutputStream(), pd);
    }
}
