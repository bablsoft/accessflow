package com.bablsoft.accessflow.core.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Per-user preferences. Currently only the user-selectable UI language; future preferences can
 * extend this interface.
 */
public interface UserPreferenceService {

    /**
     * Returns the user's persisted {@code preferred_language} as a BCP-47 code, or
     * {@link Optional#empty()} when the column is null.
     */
    Optional<String> findPreferredLanguage(UUID userId);

    /**
     * Sets the user's preferred language. The {@code language} must be in the organization's
     * {@link LocalizationConfigService#getOrDefault(UUID) available languages}.
     *
     * @throws UserNotFoundException                 if the user does not exist
     * @throws UnsupportedLanguageException          if {@code language} is not a {@link SupportedLanguage}
     * @throws LanguageNotInAllowedListException     if {@code language} is not in the org allow-list
     */
    void setPreferredLanguage(UUID userId, UUID organizationId, String language);
}
