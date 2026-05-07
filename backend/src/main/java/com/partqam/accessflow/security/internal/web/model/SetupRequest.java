package com.partqam.accessflow.security.internal.web.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SetupRequest(
        @NotBlank @Size(max = 255) String organizationName,
        @NotBlank @Email @Size(max = 255) String email,
        @Size(max = 255) String displayName,
        @NotBlank @Size(min = 8, max = 128) String password
) {}
