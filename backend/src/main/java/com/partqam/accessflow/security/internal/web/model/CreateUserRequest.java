package com.partqam.accessflow.security.internal.web.model;

import com.partqam.accessflow.core.api.UserRoleType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank(message = "{validation.email.required}")
        @Email(message = "{validation.email.invalid}")
        @Size(max = 255, message = "{validation.display_name.max}") String email,
        @NotBlank(message = "{validation.password.size}")
        @Size(min = 8, max = 128, message = "{validation.password.size}") String password,
        @Size(max = 255, message = "{validation.display_name.max}") String displayName,
        @NotNull(message = "{validation.role.required}") UserRoleType role
) {}
