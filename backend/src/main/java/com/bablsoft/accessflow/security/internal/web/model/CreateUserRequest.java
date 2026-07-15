package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.UserRoleType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateUserRequest(
        @NotBlank(message = "{validation.email.required}")
        @Email(message = "{validation.email.invalid}")
        @Size(max = 255, message = "{validation.display_name.max}") String email,
        @NotBlank(message = "{validation.password.size}")
        @Size(min = 8, max = 128, message = "{validation.password.size}") String password,
        @Size(max = 255, message = "{validation.display_name.max}") String displayName,
        UserRoleType role,
        UUID roleId
) {
    @AssertTrue(message = "{validation.role.required}")
    public boolean isRoleProvided() {
        return role != null || roleId != null;
    }
}
