package com.partqam.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Read DTO for {@link LocalizationConfigService}. {@code availableLanguages} is the org-admin's
 * allow-list of BCP-47 codes that users may pick from. {@code defaultLanguage} is the fallback for
 * users without a {@code preferred_language}. {@code aiReviewLanguage} controls the language the
 * AI analyzer responds in for every query in the org.
 */
public record LocalizationConfigView(
        UUID organizationId,
        List<String> availableLanguages,
        String defaultLanguage,
        String aiReviewLanguage
) {}
