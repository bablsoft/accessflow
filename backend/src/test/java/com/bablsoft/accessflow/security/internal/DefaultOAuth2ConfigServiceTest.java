package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.OAuth2ConfigInvalidException;
import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import com.bablsoft.accessflow.security.api.UpdateOAuth2ConfigCommand;
import com.bablsoft.accessflow.security.internal.oauth2.OAuth2ConfigDeletedEvent;
import com.bablsoft.accessflow.security.internal.oauth2.OAuth2ConfigUpdatedEvent;
import com.bablsoft.accessflow.security.internal.persistence.entity.OAuth2ConfigEntity;
import com.bablsoft.accessflow.security.internal.persistence.repo.OAuth2ConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultOAuth2ConfigServiceTest {

    @Mock OAuth2ConfigRepository repository;
    @Mock CredentialEncryptionService encryptionService;
    @Mock ApplicationEventPublisher publisher;
    @Mock MessageSource messageSource;

    private DefaultOAuth2ConfigService service;
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultOAuth2ConfigService(repository, encryptionService, publisher, messageSource);
    }

    @Test
    void listReturnsOneEntryPerProviderEvenWhenNoneSaved() {
        when(repository.findAllByOrganizationId(orgId)).thenReturn(List.of());

        var entries = service.list(orgId);

        assertThat(entries).hasSize(OAuth2ProviderType.values().length);
        assertThat(entries.stream().map(v -> v.provider()).toList())
                .containsExactlyInAnyOrder(OAuth2ProviderType.values());
        assertThat(entries).allSatisfy(v -> {
            assertThat(v.active()).isFalse();
            assertThat(v.clientSecretConfigured()).isFalse();
        });
    }

    @Test
    void getOrDefaultReturnsTransientDefaults() {
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GOOGLE))
                .thenReturn(Optional.empty());

        var view = service.getOrDefault(orgId, OAuth2ProviderType.GOOGLE);

        assertThat(view.provider()).isEqualTo(OAuth2ProviderType.GOOGLE);
        assertThat(view.active()).isFalse();
        assertThat(view.clientId()).isNull();
        assertThat(view.defaultRole()).isEqualTo(UserRoleType.ANALYST);
    }

    @Test
    void updateUpsertsAndEncryptsSecretOnChange() {
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GOOGLE))
                .thenReturn(Optional.empty());
        when(repository.save(any(OAuth2ConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encryptionService.encrypt("secret123")).thenReturn("ENC(secret123)");

        var view = service.update(orgId, OAuth2ProviderType.GOOGLE, new UpdateOAuth2ConfigCommand(
                "client-abc", "secret123", "openid email", null, UserRoleType.REVIEWER, true));

        assertThat(view.provider()).isEqualTo(OAuth2ProviderType.GOOGLE);
        assertThat(view.clientId()).isEqualTo("client-abc");
        assertThat(view.clientSecretConfigured()).isTrue();
        assertThat(view.active()).isTrue();
        assertThat(view.defaultRole()).isEqualTo(UserRoleType.REVIEWER);
        verify(encryptionService).encrypt("secret123");
        verify(publisher).publishEvent(any(OAuth2ConfigUpdatedEvent.class));
    }

    @Test
    void updateLeavesSecretWhenMaskedPlaceholderProvided() {
        var entity = seeded(OAuth2ProviderType.GITHUB);
        entity.setClientId("existing");
        entity.setClientSecretEncrypted("ENC(prior)");
        entity.setActive(true);
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GITHUB))
                .thenReturn(Optional.of(entity));
        when(repository.save(any(OAuth2ConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(orgId, OAuth2ProviderType.GITHUB,
                new UpdateOAuth2ConfigCommand(null, UpdateOAuth2ConfigCommand.MASKED_SECRET,
                        null, null, UserRoleType.ANALYST, true));

        var captor = ArgumentCaptor.forClass(OAuth2ConfigEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getClientSecretEncrypted()).isEqualTo("ENC(prior)");
        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    void updateClearsSecretAndDeactivatesWhenBlankProvided() {
        var entity = seeded(OAuth2ProviderType.GITLAB);
        entity.setClientId("existing");
        entity.setClientSecretEncrypted("ENC(prior)");
        entity.setActive(true);
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GITLAB))
                .thenReturn(Optional.of(entity));
        when(repository.save(any(OAuth2ConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var view = service.update(orgId, OAuth2ProviderType.GITLAB,
                new UpdateOAuth2ConfigCommand(null, "", null, null, UserRoleType.ANALYST, null));

        assertThat(view.clientSecretConfigured()).isFalse();
        assertThat(view.active()).isFalse();
    }

    @Test
    void updateRejectsActivationWithoutClientId() {
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GOOGLE))
                .thenReturn(Optional.empty());
        when(messageSource.getMessage(anyString(), any(), any())).thenReturn("client_id required");

        assertThatThrownBy(() -> service.update(orgId, OAuth2ProviderType.GOOGLE,
                new UpdateOAuth2ConfigCommand(null, null, null, null, UserRoleType.ANALYST, true)))
                .isInstanceOf(OAuth2ConfigInvalidException.class);
    }

    @Test
    void updateRejectsMicrosoftActivationWithoutTenant() {
        when(repository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.MICROSOFT))
                .thenReturn(Optional.empty());
        when(encryptionService.encrypt("s")).thenReturn("E");
        when(messageSource.getMessage(anyString(), any(), any())).thenReturn("tenant required");

        assertThatThrownBy(() -> service.update(orgId, OAuth2ProviderType.MICROSOFT,
                new UpdateOAuth2ConfigCommand("c", "s", null, null, UserRoleType.ANALYST, true)))
                .isInstanceOf(OAuth2ConfigInvalidException.class)
                .hasMessageContaining("tenant");
    }

    @Test
    void listActivePublishesOnlyEnabledRows() {
        var enabled = seeded(OAuth2ProviderType.GOOGLE);
        enabled.setActive(true);
        when(repository.findAllByOrganizationIdAndActiveTrue(orgId))
                .thenReturn(List.of(enabled));

        var active = service.listActive(orgId);

        assertThat(active).singleElement().satisfies(s -> {
            assertThat(s.provider()).isEqualTo(OAuth2ProviderType.GOOGLE);
            assertThat(s.displayName()).isEqualTo("Google");
        });
    }

    @Test
    void deletePublishesEvent() {
        service.delete(orgId, OAuth2ProviderType.GITHUB);

        verify(repository).deleteByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GITHUB);
        verify(publisher).publishEvent(any(OAuth2ConfigDeletedEvent.class));
    }

    private OAuth2ConfigEntity seeded(OAuth2ProviderType provider) {
        var entity = new OAuth2ConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setProvider(provider);
        entity.setDefaultRole(UserRoleType.ANALYST);
        return entity;
    }
}
