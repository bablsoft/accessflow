package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record ApiKeyCreateRequest(
        @NotBlank(message = "{validation.api_key.name.required}")
        @Size(min = 1, max = 100, message = "{validation.api_key.name.size}")
        String name,
        Instant expiresAt
) {}
