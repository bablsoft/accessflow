package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank(message = "{validation.new_password.required}")
        @Size(min = 8, max = 128, message = "{validation.new_password.size}") String password
) {}
