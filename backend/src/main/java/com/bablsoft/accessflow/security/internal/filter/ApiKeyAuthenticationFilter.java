package com.bablsoft.accessflow.security.internal.filter;

import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.core.api.RolePermissionResolver;
import com.bablsoft.accessflow.core.api.UserProfileService;
import com.bablsoft.accessflow.security.api.ApiKeyService;
import com.bablsoft.accessflow.security.api.JwtClaims;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Authenticates requests presenting an AccessFlow API key, via either
 * {@code X-API-Key: <rawKey>} or {@code Authorization: ApiKey <rawKey>}. On success, populates the
 * security context with an {@link ApiKeyAuthenticationToken} carrying the same {@link JwtClaims}
 * shape produced by the JWT path, so downstream controllers and MCP tools are auth-agnostic.
 *
 * <p>{@code SecurityConfiguration} registers this filter before {@code JwtAuthenticationFilter};
 * if no API key header is present (or it doesn't resolve), the JWT filter still gets a chance to
 * authenticate the request. Failures are silent — the security entry point handles unauthenticated
 * requests.
 */
@Component
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";
    public static final String AUTHORIZATION_API_KEY_SCHEME = "ApiKey ";

    private final ApiKeyService apiKeyService;
    private final UserProfileService userProfileService;
    private final OrganizationLookupService organizationLookupService;
    private final RolePermissionResolver rolePermissionResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            var rawKey = extractApiKey(request);
            if (rawKey != null) {
                resolveClaims(rawKey).ifPresent(claims -> SecurityContextHolder.getContext()
                        .setAuthentication(new ApiKeyAuthenticationToken(claims)));
            }
        }
        filterChain.doFilter(request, response);
    }

    private Optional<JwtClaims> resolveClaims(String rawKey) {
        return apiKeyService.resolveUserId(rawKey).flatMap(this::loadClaims);
    }

    private Optional<JwtClaims> loadClaims(UUID userId) {
        try {
            var user = userProfileService.getProfile(userId);
            if (!user.active() || organizationLookupService.isDisabled(user.organizationId())) {
                return Optional.empty();
            }
            // API-key callers get per-request permission resolution — a role edit takes effect on
            // the caller's next request, unlike the JWT path's mint-time snapshot (AF-522).
            var permissions = rolePermissionResolver.resolve(user.roleId(), user.role());
            return Optional.of(new JwtClaims(
                    user.id(), user.email(), user.role(), user.roleId(), user.roleName(),
                    permissions, user.organizationId(), user.platformAdmin()));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private String extractApiKey(HttpServletRequest request) {
        var header = request.getHeader(API_KEY_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        var authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith(AUTHORIZATION_API_KEY_SCHEME)) {
            var candidate = authorization.substring(AUTHORIZATION_API_KEY_SCHEME.length()).trim();
            return candidate.isEmpty() ? null : candidate;
        }
        return null;
    }
}
