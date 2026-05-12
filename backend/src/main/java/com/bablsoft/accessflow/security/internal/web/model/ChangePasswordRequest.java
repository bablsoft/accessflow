package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "{validation.current_password.required}")
        String currentPassword,
        @NotBlank(message = "{validation.new_password.required}")
        @Size(min = 8, max = 128, message = "{validation.new_password.size}")
        String newPassword
) {}
