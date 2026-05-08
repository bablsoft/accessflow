package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.IllegalLocalizationConfigException;
import com.partqam.accessflow.core.api.UnsupportedLanguageException;
import com.partqam.accessflow.core.api.UpdateLocalizationConfigCommand;
import com.partqam.accessflow.core.internal.persistence.entity.LocalizationConfigEntity;
import com.partqam.accessflow.core.internal.persistence.repo.LocalizationConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultLocalizationConfigServiceTest {

    @Mock LocalizationConfigRepository repository;

    private DefaultLocalizationConfigService service;

    @BeforeEach
    void setUp() {
        var messageSource = new StaticMessageSource();
        messageSource.setUseCodeAsDefaultMessage(true);
        service = new DefaultLocalizationConfigService(repository, messageSource);
    }

    @Test
    void getOrDefaultReturnsPersistedRow() {
        var orgId = UUID.randomUUID();
        var entity = new LocalizationConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setAvailableLanguages(List.of("en", "es"));
        entity.setDefaultLanguage("en");
        entity.setAiReviewLanguage("es");
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));

        var view = service.getOrDefault(orgId);

        assertThat(view.organizationId()).isEqualTo(orgId);
        assertThat(view.availableLanguages()).containsExactly("en", "es");
        assertThat(view.defaultLanguage()).isEqualTo("en");
        assertThat(view.aiReviewLanguage()).isEqualTo("es");
    }

    @Test
    void getOrDefaultReturnsTransientEnglishWhenMissing() {
        var orgId = UUID.randomUUID();
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.empty());

        var view = service.getOrDefault(orgId);

        assertThat(view.organizationId()).isEqualTo(orgId);
        assertThat(view.availableLanguages()).containsExactly("en");
        assertThat(view.defaultLanguage()).isEqualTo("en");
        assertThat(view.aiReviewLanguage()).isEqualTo("en");
    }

    @Test
    void updateInsertsWhenNoExistingRow() {
        var orgId = UUID.randomUUID();
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.update(orgId, new UpdateLocalizationConfigCommand(
                List.of("en", "es"), "en", "es"));

        var captor = ArgumentCaptor.forClass(LocalizationConfigEntity.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId()).isEqualTo(orgId);
        assertThat(captor.getValue().getAvailableLanguages()).containsExactly("en", "es");
        assertThat(view.aiReviewLanguage()).isEqualTo("es");
    }

    @Test
    void updateUpdatesExistingRow() {
        var orgId = UUID.randomUUID();
        var existing = new LocalizationConfigEntity();
        existing.setId(UUID.randomUUID());
        existing.setOrganizationId(orgId);
        existing.setAvailableLanguages(List.of("en"));
        existing.setDefaultLanguage("en");
        existing.setAiReviewLanguage("en");
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(orgId, new UpdateLocalizationConfigCommand(
                List.of("en", "fr"), "fr", "fr"));

        assertThat(existing.getAvailableLanguages()).containsExactly("en", "fr");
        assertThat(existing.getDefaultLanguage()).isEqualTo("fr");
        assertThat(existing.getAiReviewLanguage()).isEqualTo("fr");
    }

    @Test
    void updateDeduplicatesAvailableLanguages() {
        var orgId = UUID.randomUUID();
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.update(orgId, new UpdateLocalizationConfigCommand(
                List.of("en", "es", "en"), "en", "en"));

        assertThat(view.availableLanguages()).containsExactly("en", "es");
    }

    @Test
    void updateRejectsEmptyAvailableLanguages() {
        var orgId = UUID.randomUUID();

        assertThatThrownBy(() -> service.update(orgId, new UpdateLocalizationConfigCommand(
                List.of(), "en", "en")))
                .isInstanceOf(IllegalLocalizationConfigException.class);
    }

    @Test
    void updateRejectsUnsupportedLanguageInAvailableList() {
        var orgId = UUID.randomUUID();

        assertThatThrownBy(() -> service.update(orgId, new UpdateLocalizationConfigCommand(
                List.of("en", "xx"), "en", "en")))
                .isInstanceOf(UnsupportedLanguageException.class);
    }

    @Test
    void updateRejectsUnsupportedDefaultLanguage() {
        var orgId = UUID.randomUUID();

        assertThatThrownBy(() -> service.update(orgId, new UpdateLocalizationConfigCommand(
                List.of("en"), "xx", "en")))
                .isInstanceOf(UnsupportedLanguageException.class);
    }

    @Test
    void updateRejectsUnsupportedAiReviewLanguage() {
        var orgId = UUID.randomUUID();

        assertThatThrownBy(() -> service.update(orgId, new UpdateLocalizationConfigCommand(
                List.of("en"), "en", "xx")))
                .isInstanceOf(UnsupportedLanguageException.class);
    }

    @Test
    void updateRejectsDefaultLanguageNotInAvailable() {
        var orgId = UUID.randomUUID();

        assertThatThrownBy(() -> service.update(orgId, new UpdateLocalizationConfigCommand(
                List.of("en"), "es", "en")))
                .isInstanceOf(IllegalLocalizationConfigException.class);
    }
}
