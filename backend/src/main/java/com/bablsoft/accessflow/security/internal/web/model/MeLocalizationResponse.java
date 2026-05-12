package com.bablsoft.accessflow.security.internal.web.model;

import java.util.List;

public record MeLocalizationResponse(
        List<String> availableLanguages,
        String defaultLanguage,
        String currentLanguage
) {}
