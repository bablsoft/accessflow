package com.bablsoft.accessflow.scim.internal.web.scim;

import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.scim.api.ScimConfigService;
import com.bablsoft.accessflow.scim.api.ScimTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates {@code /scim/v2/**} requests by their per-org bearer token (#621). Enforced per
 * request, exactly like the JWT and API-key filters: a revoked token, a disabled SCIM config, or
 * a disabled organization all leave the security context empty, and
 * {@link ScimAuthenticationEntryPoint} answers 401 in the SCIM error envelope. The org is derived
 * from the token — never from the request.
 */
@Component
@RequiredArgsConstructor
public class ScimTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ScimTokenService tokenService;
    private final ScimConfigService configService;
    private final OrganizationLookupService organizationLookupService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            var rawToken = extractBearer(request);
            if (rawToken != null) {
                tokenService.authenticate(rawToken)
                        .filter(p -> configService.isEnabled(p.organizationId()))
                        .filter(p -> !organizationLookupService.isDisabled(p.organizationId()))
                        .ifPresent(p -> SecurityContextHolder.getContext()
                                .setAuthentication(new ScimAuthenticationToken(p)));
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractBearer(HttpServletRequest request) {
        var authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        var candidate = authorization.substring(BEARER_PREFIX.length()).trim();
        return candidate.isEmpty() ? null : candidate;
    }
}
