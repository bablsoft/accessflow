package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.LanguageNotInAllowedListException;
import com.bablsoft.accessflow.core.api.LocalizationConfigService;
import com.bablsoft.accessflow.core.api.LocalizationConfigView;
import com.bablsoft.accessflow.core.api.UnsupportedLanguageException;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultUserPreferenceServiceTest {

    @Mock UserRepository userRepository;
    @Mock LocalizationConfigService localizationConfigService;

    private DefaultUserPreferenceService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultUserPreferenceService(userRepository, localizationConfigService);
    }

    @Test
    void findReturnsValueWhenSet() {
        var entity = userEntity("es");
        when(userRepository.findById(userId)).thenReturn(Optional.of(entity));

        assertThat(service.findPreferredLanguage(userId)).hasValue("es");
    }

    @Test
    void findReturnsEmptyWhenNullOrBlank() {
        var entity = userEntity(null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(entity));

        assertThat(service.findPreferredLanguage(userId)).isEmpty();

        var blank = userEntity("  ");
        when(userRepository.findById(userId)).thenReturn(Optional.of(blank));
        assertThat(service.findPreferredLanguage(userId)).isEmpty();
    }

    @Test
    void findReturnsEmptyWhenUserMissing() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThat(service.findPreferredLanguage(userId)).isEmpty();
    }

    @Test
    void setRejectsUnsupportedLanguage() {
        assertThatThrownBy(() -> service.setPreferredLanguage(userId, orgId, "xx"))
                .isInstanceOf(UnsupportedLanguageException.class);
    }

    @Test
    void setRejectsLanguageNotInAllowList() {
        when(localizationConfigService.getOrDefault(orgId)).thenReturn(
                new LocalizationConfigView(orgId, List.of("en"), "en", "en"));

        assertThatThrownBy(() -> service.setPreferredLanguage(userId, orgId, "es"))
                .isInstanceOf(LanguageNotInAllowedListException.class);
    }

    @Test
    void setRejectsWhenUserMissing() {
        when(localizationConfigService.getOrDefault(orgId)).thenReturn(
                new LocalizationConfigView(orgId, List.of("en", "es"), "en", "en"));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setPreferredLanguage(userId, orgId, "es"))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void setRejectsWhenUserBelongsToDifferentOrg() {
        var otherOrg = UUID.randomUUID();
        when(localizationConfigService.getOrDefault(otherOrg)).thenReturn(
                new LocalizationConfigView(otherOrg, List.of("en", "es"), "en", "en"));
        var entity = userEntity(null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.setPreferredLanguage(userId, otherOrg, "es"))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void setPersistsAllowedLanguage() {
        when(localizationConfigService.getOrDefault(orgId)).thenReturn(
                new LocalizationConfigView(orgId, List.of("en", "ES"), "en", "en"));
        var entity = userEntity(null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(entity));

        service.setPreferredLanguage(userId, orgId, "es");

        assertThat(entity.getPreferredLanguage()).isEqualTo("es");
    }

    private UserEntity userEntity(String preferredLanguage) {
        var entity = new UserEntity();
        entity.setId(userId);
        var org = new OrganizationEntity();
        org.setId(orgId);
        entity.setOrganization(org);
        entity.setPreferredLanguage(preferredLanguage);
        return entity;
    }
}
