package com.partqam.accessflow.security.internal.web.model;

import com.partqam.accessflow.core.api.UserRoleType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @Size(max = 255) String displayName,
        @NotNull UserRoleType role
) {}
