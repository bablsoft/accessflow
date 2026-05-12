package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ConfirmTotpRequest(
        @NotBlank(message = "{validation.totp_code.required}")
        @Pattern(regexp = "^\\d{6}$", message = "{validation.totp_code.pattern}")
        String code
) {}
