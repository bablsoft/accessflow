package com.bablsoft.accessflow.mcp.internal.tools;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves the authenticated principal for an MCP tool invocation. The principal is populated by
 * either {@code ApiKeyAuthenticationFilter} or {@code JwtAuthenticationFilter} earlier in the
 * filter chain; here we just unwrap it from the SecurityContext.
 */
@Component
public class McpCurrentUser {

    public JwtClaims requireClaims() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            throw new McpAuthenticationException("MCP request is not authenticated");
        }
        var principal = auth.getPrincipal();
        if (!(principal instanceof JwtClaims claims)) {
            throw new McpAuthenticationException(
                    "MCP request principal is not a JwtClaims (was " + principal.getClass() + ")");
        }
        return claims;
    }

    public UUID userId() {
        return requireClaims().userId();
    }

    public UUID organizationId() {
        return requireClaims().organizationId();
    }

    public UserRoleType role() {
        return requireClaims().role();
    }

    public boolean isAdmin() {
        return role() == UserRoleType.ADMIN;
    }
}
