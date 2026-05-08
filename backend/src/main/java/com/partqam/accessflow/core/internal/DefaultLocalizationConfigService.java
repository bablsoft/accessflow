package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.IllegalLocalizationConfigException;
import com.partqam.accessflow.core.api.LocalizationConfigService;
import com.partqam.accessflow.core.api.LocalizationConfigView;
import com.partqam.accessflow.core.api.SupportedLanguage;
import com.partqam.accessflow.core.api.UnsupportedLanguageException;
import com.partqam.accessflow.core.api.UpdateLocalizationConfigCommand;
import com.partqam.accessflow.core.internal.persistence.entity.LocalizationConfigEntity;
import com.partqam.accessflow.core.internal.persistence.repo.LocalizationConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
class DefaultLocalizationConfigService implements LocalizationConfigService {

    private static final String DEFAULT_LANGUAGE = SupportedLanguage.EN.code();

    private final LocalizationConfigRepository repository;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public LocalizationConfigView getOrDefault(UUID organizationId) {
        return repository.findByOrganizationId(organizationId)
                .map(DefaultLocalizationConfigService::toView)
                .orElseGet(() -> defaultView(organizationId));
    }

    @Override
    @Transactional
    public LocalizationConfigView update(UUID organizationId, UpdateLocalizationConfigCommand command) {
        validate(command);

        var deduplicated = new ArrayList<>(new java.util.LinkedHashSet<>(command.availableLanguages()));

        var entity = repository.findByOrganizationId(organizationId)
                .orElseGet(() -> {
                    var fresh = new LocalizationConfigEntity();
                    fresh.setId(UUID.randomUUID());
                    fresh.setOrganizationId(organizationId);
                    return fresh;
                });
        entity.setAvailableLanguages(deduplicated);
        entity.setDefaultLanguage(command.defaultLanguage());
        entity.setAiReviewLanguage(command.aiReviewLanguage());
        entity.setUpdatedAt(Instant.now());
        return toView(repository.save(entity));
    }

    private void validate(UpdateLocalizationConfigCommand command) {
        if (command.availableLanguages() == null || command.availableLanguages().isEmpty()) {
            throw new IllegalLocalizationConfigException(
                    messageSource.getMessage("validation.available_languages.empty", null,
                            LocaleContextHolder.getLocale()));
        }
        for (var code : command.availableLanguages()) {
            if (SupportedLanguage.fromCode(code).isEmpty()) {
                throw new UnsupportedLanguageException(code);
            }
        }
        if (command.defaultLanguage() == null
                || SupportedLanguage.fromCode(command.defaultLanguage()).isEmpty()) {
            throw new UnsupportedLanguageException(command.defaultLanguage());
        }
        if (command.aiReviewLanguage() == null
                || SupportedLanguage.fromCode(command.aiReviewLanguage()).isEmpty()) {
            throw new UnsupportedLanguageException(command.aiReviewLanguage());
        }
        Set<String> codes = command.availableLanguages().stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        if (!codes.contains(command.defaultLanguage().toLowerCase())) {
            throw new IllegalLocalizationConfigException(
                    messageSource.getMessage("validation.default_language.invalid", null,
                            LocaleContextHolder.getLocale()));
        }
    }

    private static LocalizationConfigView toView(LocalizationConfigEntity entity) {
        return new LocalizationConfigView(
                entity.getOrganizationId(),
                List.copyOf(entity.getAvailableLanguages()),
                entity.getDefaultLanguage(),
                entity.getAiReviewLanguage()
        );
    }

    private static LocalizationConfigView defaultView(UUID organizationId) {
        return new LocalizationConfigView(
                organizationId,
                List.of(DEFAULT_LANGUAGE),
                DEFAULT_LANGUAGE,
                DEFAULT_LANGUAGE
        );
    }
}
