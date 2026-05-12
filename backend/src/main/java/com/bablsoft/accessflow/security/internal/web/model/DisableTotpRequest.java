package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.NotBlank;

public record DisableTotpRequest(
        @NotBlank(message = "{validation.current_password.required}")
        String currentPassword
) {}
