package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.LocalizationConfigView;

import java.util.List;
import java.util.UUID;

public record LocalizationConfigResponse(
        UUID organizationId,
        List<String> availableLanguages,
        String defaultLanguage,
        String aiReviewLanguage
) {
    public static LocalizationConfigResponse from(LocalizationConfigView view) {
        return new LocalizationConfigResponse(
                view.organizationId(),
                view.availableLanguages(),
                view.defaultLanguage(),
                view.aiReviewLanguage()
        );
    }
}
