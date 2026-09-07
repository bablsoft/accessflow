package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SetupRequest(
        @NotBlank(message = "{validation.org_name.required}")
        @Size(max = 255, message = "{validation.display_name.max}") String organizationName,
        @NotBlank(message = "{validation.email.required}")
        @Email(message = "{validation.email.invalid}")
        @Size(max = 255, message = "{validation.display_name.max}") String email,
        @Size(max = 255, message = "{validation.display_name.max}") String displayName,
        @NotBlank(message = "{validation.password.size}")
        @Size(min = 8, max = 128, message = "{validation.password.size}") String password,
        // Boxed, with defaulted accessors below: an ABSENT primitive boolean fails Jackson 3's
        // FAIL_ON_NULL_FOR_PRIMITIVES, and both flags are optional in the wizard (AF-898).
        Boolean governsApis,
        Boolean governsDeployments
) {

    public boolean governsApisOrDefault() {
        return Boolean.TRUE.equals(governsApis);
    }

    public boolean governsDeploymentsOrDefault() {
        return Boolean.TRUE.equals(governsDeployments);
    }
}
