package com.bablsoft.accessflow.core.api;

import java.util.UUID;

/**
 * Org-singleton localization settings: which languages are exposed to users, the default language
 * for new accounts, and the language used by the AI analyzer when reviewing SQL.
 *
 * <p>{@link #getOrDefault} returns the persisted row or a transient default with English as the
 * sole available, default, and AI-review language. {@link #update} performs an upsert and validates
 * every code against {@link SupportedLanguage}.
 *
 * <p>Per-user preferences (read/write) are managed by {@link UserPreferenceService}.
 */
public interface LocalizationConfigService {

    LocalizationConfigView getOrDefault(UUID organizationId);

    LocalizationConfigView update(UUID organizationId, UpdateLocalizationConfigCommand command);

    /**
     * Public view exposed to unauthenticated callers (the login page). The {@code availableLanguages}
     * list is the union across every persisted org, so a multi-tenant deployment never reveals which
     * organizations exist. Returns {@code ["en"]} / {@code "en"} when no org has saved a config yet.
     */
    PublicLocalizationConfigView getPublicConfig();
}
