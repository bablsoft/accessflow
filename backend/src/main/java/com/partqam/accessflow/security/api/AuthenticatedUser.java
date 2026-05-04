package com.partqam.accessflow.security.api;

import com.partqam.accessflow.core.api.UserRoleType;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

public final class AuthenticatedUser {

    private AuthenticatedUser() {}

    public static JwtClaims claims() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof JwtClaims)) {
            throw new IllegalStateException("No authenticated user in security context");
        }
        return (JwtClaims) auth.getPrincipal();
    }

    public static UUID currentUserId() {
        return claims().userId();
    }

    public static String currentEmail() {
        return claims().email();
    }

    public static UserRoleType currentRole() {
        return claims().role();
    }

    public static UUID currentOrganizationId() {
        return claims().organizationId();
    }
}
