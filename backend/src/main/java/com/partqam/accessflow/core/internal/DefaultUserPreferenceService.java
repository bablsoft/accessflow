package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.LanguageNotInAllowedListException;
import com.partqam.accessflow.core.api.LocalizationConfigService;
import com.partqam.accessflow.core.api.SupportedLanguage;
import com.partqam.accessflow.core.api.UnsupportedLanguageException;
import com.partqam.accessflow.core.api.UserNotFoundException;
import com.partqam.accessflow.core.api.UserPreferenceService;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultUserPreferenceService implements UserPreferenceService {

    private final UserRepository userRepository;
    private final LocalizationConfigService localizationConfigService;

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findPreferredLanguage(UUID userId) {
        return userRepository.findById(userId)
                .map(u -> u.getPreferredLanguage())
                .filter(s -> s != null && !s.isBlank());
    }

    @Override
    @Transactional
    public void setPreferredLanguage(UUID userId, UUID organizationId, String language) {
        if (SupportedLanguage.fromCode(language).isEmpty()) {
            throw new UnsupportedLanguageException(language);
        }
        var allowed = localizationConfigService.getOrDefault(organizationId).availableLanguages();
        if (allowed.stream().noneMatch(c -> c.equalsIgnoreCase(language))) {
            throw new LanguageNotInAllowedListException(language);
        }
        var entity = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        if (!entity.getOrganization().getId().equals(organizationId)) {
            throw new UserNotFoundException(userId);
        }
        entity.setPreferredLanguage(language);
    }
}
