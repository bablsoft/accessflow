package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateMeProfileRequest(
        @NotBlank(message = "{validation.display_name.required}")
        @Size(max = 255, message = "{validation.display_name.max}")
        String displayName
) {}
