package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.NotBlank;

public record UpdateMeLocalizationRequest(
        @NotBlank(message = "{validation.language.required}") String language
) {}
