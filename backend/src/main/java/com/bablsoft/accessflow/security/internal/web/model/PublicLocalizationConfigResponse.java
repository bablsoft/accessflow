package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.PublicLocalizationConfigView;

import java.util.List;

public record PublicLocalizationConfigResponse(
        List<String> availableLanguages,
        String defaultLanguage
) {
    public static PublicLocalizationConfigResponse from(PublicLocalizationConfigView view) {
        return new PublicLocalizationConfigResponse(view.availableLanguages(), view.defaultLanguage());
    }
}
