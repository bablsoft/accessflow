package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.UserRoleType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InviteUserRequest(
        @NotBlank(message = "{validation.invite.email.required}")
        @Email(message = "{validation.email.invalid}")
        @Size(max = 255, message = "{validation.display_name.max}") String email,
        @Size(max = 255, message = "{validation.display_name.max}") String displayName,
        @NotNull(message = "{validation.invite.role.required}") UserRoleType role
) {}
