package com.bablsoft.accessflow.core.api;

import java.util.List;

/**
 * All fields are required and replace the previous values atomically.
 *
 * <ul>
 *     <li>{@code availableLanguages} — non-empty; every entry must be a {@link SupportedLanguage} code.</li>
 *     <li>{@code defaultLanguage} — must be a {@link SupportedLanguage} code <em>and</em> a member of
 *         {@code availableLanguages}.</li>
 *     <li>{@code aiReviewLanguage} — any {@link SupportedLanguage} code (independent of the user-facing allow-list).</li>
 * </ul>
 */
public record UpdateLocalizationConfigCommand(
        List<String> availableLanguages,
        String defaultLanguage,
        String aiReviewLanguage
) {}
