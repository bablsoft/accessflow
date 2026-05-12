package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "{validation.email.required}")
        @Email(message = "{validation.email.invalid}") String email,
        @NotBlank(message = "{validation.password.size}")
        @Size(min = 8, max = 128, message = "{validation.password.size}") String password,
        String totpCode
) {}
