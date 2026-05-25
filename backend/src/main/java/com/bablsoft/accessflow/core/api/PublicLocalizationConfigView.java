package com.bablsoft.accessflow.core.api;

import java.util.List;

/**
 * Read DTO for {@link LocalizationConfigService#getPublicConfig()}. Returned to unauthenticated
 * callers (the login page) so they can render a language selector before any user has signed in.
 *
 * <p>{@code availableLanguages} is the union of every persisted org's allow-list (or
 * {@code ["en"]} if no org has saved a config yet). {@code defaultLanguage} comes from the most
 * recently updated row, falling back to {@code "en"}. Organization identity is intentionally not
 * exposed — a logged-out caller must not learn which tenants exist.
 */
public record PublicLocalizationConfigView(
        List<String> availableLanguages,
        String defaultLanguage
) {}
