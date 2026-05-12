package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.UpdateLocalizationConfigCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UpdateLocalizationConfigRequest(
        @NotNull(message = "{validation.available_languages.empty}")
        @NotEmpty(message = "{validation.available_languages.empty}")
        List<@NotBlank(message = "{validation.language.required}") String> availableLanguages,

        @NotBlank(message = "{validation.language.required}")
        String defaultLanguage,

        @NotBlank(message = "{validation.language.required}")
        String aiReviewLanguage
) {
    public UpdateLocalizationConfigCommand toCommand() {
        return new UpdateLocalizationConfigCommand(availableLanguages, defaultLanguage, aiReviewLanguage);
    }
}
