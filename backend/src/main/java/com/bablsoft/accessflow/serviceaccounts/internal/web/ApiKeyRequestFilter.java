package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.security.api.ApiKeyAuthentication;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountRateLimitExceededException;
import com.bablsoft.accessflow.serviceaccounts.internal.OnBehalfOfResolver;
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
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;

/**
 * The per-request guard on API-key traffic. Two jobs, in order:
 * <ol>
 *   <li>the per-identity rate limit on every API-key-authenticated request (#873) — {@code /mcp/**}
 *       and {@code /api/v1/**} alike, never a JWT browser session;</li>
 *   <li>the optional {@code X-AccessFlow-On-Behalf-Of} header (#874): resolved to a human the
 *       calling service account holds a live delegation for, then parked in the request attribute
 *       {@link #ON_BEHALF_OF_ATTRIBUTE} — never on the {@code Authentication}, which is left
 *       untouched so the permission set can only ever be the key owner's. Any failure is a loud
 *       403 ({@code ON_BEHALF_OF_NOT_PERMITTED}), never a silent drop, and on a review / decision
 *       path the header is refused outright ({@code ON_BEHALF_OF_REVIEW_FORBIDDEN}).</li>
 * </ol>
 * Registered by
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

    /** The inbound header naming the human an API-key caller acts for (#874). */
    public static final String ON_BEHALF_OF_HEADER = "X-AccessFlow-On-Behalf-Of";
    /**
     * Request attribute holding the resolved principal's user id — read only through
     * {@code OnBehalfOfPrincipalService}; deliberately not a security-context value.
     */
    public static final String ON_BEHALF_OF_ATTRIBUTE = ApiKeyRequestFilter.class.getName() + ".onBehalfOf";
    static final String ON_BEHALF_OF_NOT_PERMITTED = "ON_BEHALF_OF_NOT_PERMITTED";
    static final String ON_BEHALF_OF_REVIEW_FORBIDDEN = "ON_BEHALF_OF_REVIEW_FORBIDDEN";
    static final String REASON_NOT_API_KEY = "not_api_key";
    static final String REASON_NOT_PERMITTED = "not_permitted";
    static final String ON_BEHALF_OF_NOT_PERMITTED_KEY_PREFIX = "error.on_behalf_of_not_permitted.";
    static final String ON_BEHALF_OF_REVIEW_FORBIDDEN_KEY = "error.on_behalf_of_review_forbidden";

    private final ServiceAccountRateLimiter rateLimiter;
    private final OnBehalfOfResolver onBehalfOfResolver;
    private final OnBehalfOfDecisionPaths decisionPaths;
    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;
    private final Clock clock;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var apiKeyClaims = authentication instanceof ApiKeyAuthentication
                && authentication.getPrincipal() instanceof JwtClaims claims ? claims : null;
        if (apiKeyClaims != null) {
            try {
                rateLimiter.enforce(apiKeyClaims.userId());
            } catch (ServiceAccountRateLimitExceededException ex) {
                writeTooManyRequests(request, response, ex);
                return;
            }
        }
        var onBehalfOf = request.getHeader(ON_BEHALF_OF_HEADER);
        // Only a real caller can be told off for the header: on a permitAll path (/actuator/health,
        // /auth/*) the context holds an AnonymousAuthenticationToken — whose isAuthenticated() is
        // true — and a stray header is simply ignored.
        if (onBehalfOf != null && authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            if (apiKeyClaims == null) {
                writeForbidden(request, response, ON_BEHALF_OF_NOT_PERMITTED,
                        ON_BEHALF_OF_NOT_PERMITTED_KEY_PREFIX + REASON_NOT_API_KEY, REASON_NOT_API_KEY);
                return;
            }
            if (decisionPaths.isDecisionPath(request)) {
                writeForbidden(request, response, ON_BEHALF_OF_REVIEW_FORBIDDEN,
                        ON_BEHALF_OF_REVIEW_FORBIDDEN_KEY, null);
                return;
            }
            var principal = onBehalfOfResolver.resolve(apiKeyClaims.userId(), apiKeyClaims.organizationId(),
                    onBehalfOf);
            if (principal.isEmpty()) {
                writeForbidden(request, response, ON_BEHALF_OF_NOT_PERMITTED,
                        ON_BEHALF_OF_NOT_PERMITTED_KEY_PREFIX + REASON_NOT_PERMITTED, REASON_NOT_PERMITTED);
                return;
            }
            request.setAttribute(ON_BEHALF_OF_ATTRIBUTE, principal.get());
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
        stampAndWrite(response, pd);
    }

    private void writeForbidden(HttpServletRequest request, HttpServletResponse response, String code,
                                String messageKey, String reason) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                messageSource.getMessage(messageKey, null, request.getLocale()));
        pd.setProperty("error", code);
        if (reason != null) {
            pd.setProperty("reason", reason);
        }
        stampAndWrite(response, pd);
    }

    private void stampAndWrite(HttpServletResponse response, ProblemDetail pd) throws IOException {
        pd.setProperty("timestamp", clock.instant().toString());
        var traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            pd.setProperty("traceId", traceId);
        }
        objectMapper.writeValue(response.getOutputStream(), pd);
    }
}
