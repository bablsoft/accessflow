package com.bablsoft.accessflow.security.internal.oauth2;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import com.bablsoft.accessflow.security.internal.persistence.entity.OAuth2ConfigEntity;
import com.bablsoft.accessflow.security.internal.persistence.repo.OAuth2ConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicClientRegistrationRepositoryTest {

    @Mock OAuth2ConfigRepository configRepository;
    @Mock CredentialEncryptionService encryptionService;
    @Mock OrganizationLookupService organizationLookupService;

    private DynamicClientRegistrationRepository repository;
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = new DynamicClientRegistrationRepository(
                configRepository, encryptionService, organizationLookupService);
    }

    @Test
    void returnsNullForUnknownRegistrationId() {
        assertThat(repository.findByRegistrationId("unknown")).isNull();
        assertThat(repository.findByRegistrationId(null)).isNull();
        assertThat(repository.findByRegistrationId("")).isNull();
    }

    @Test
    void returnsNullWhenNoActiveRowExists() {
        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(configRepository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GOOGLE))
                .thenReturn(Optional.empty());

        assertThat(repository.findByRegistrationId("google")).isNull();
    }

    @Test
    void returnsNullWhenRowExistsButInactive() {
        var entity = seeded(OAuth2ProviderType.GOOGLE);
        entity.setActive(false);
        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(configRepository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GOOGLE))
                .thenReturn(Optional.of(entity));

        assertThat(repository.findByRegistrationId("google")).isNull();
    }

    @Test
    void buildsClientRegistrationFromActiveRow() {
        var entity = seeded(OAuth2ProviderType.GOOGLE);
        entity.setClientId("client-id-value");
        entity.setClientSecretEncrypted("ENC(secret)");
        entity.setActive(true);
        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(configRepository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GOOGLE))
                .thenReturn(Optional.of(entity));
        when(encryptionService.decrypt("ENC(secret)")).thenReturn("plain-secret");

        var registration = repository.findByRegistrationId("google");

        assertThat(registration).isNotNull();
        assertThat(registration.getClientId()).isEqualTo("client-id-value");
        assertThat(registration.getClientSecret()).isEqualTo("plain-secret");
        assertThat(registration.getProviderDetails().getAuthorizationUri())
                .startsWith("https://accounts.google.com");
        assertThat(registration.getScopes()).contains("openid", "email", "profile");
    }

    @Test
    void appliesScopesOverride() {
        var entity = seeded(OAuth2ProviderType.GOOGLE);
        entity.setClientId("c");
        entity.setClientSecretEncrypted("E");
        entity.setActive(true);
        entity.setScopesOverride("openid profile");
        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(configRepository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GOOGLE))
                .thenReturn(Optional.of(entity));
        when(encryptionService.decrypt("E")).thenReturn("s");

        var registration = repository.findByRegistrationId("google");

        assertThat(registration.getScopes()).containsExactlyInAnyOrder("openid", "profile");
    }

    @Test
    void substitutesMicrosoftTenant() {
        var entity = seeded(OAuth2ProviderType.MICROSOFT);
        entity.setClientId("c");
        entity.setClientSecretEncrypted("E");
        entity.setActive(true);
        entity.setTenantId("contoso");
        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(configRepository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.MICROSOFT))
                .thenReturn(Optional.of(entity));
        when(encryptionService.decrypt("E")).thenReturn("s");

        var registration = repository.findByRegistrationId("microsoft");

        assertThat(registration.getProviderDetails().getAuthorizationUri())
                .contains("/contoso/");
    }

    @Test
    void cachesRegistrationsAndEvictsOnUpdate() {
        var entity = seeded(OAuth2ProviderType.GOOGLE);
        entity.setClientId("c");
        entity.setClientSecretEncrypted("E");
        entity.setActive(true);
        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(configRepository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GOOGLE))
                .thenReturn(Optional.of(entity));
        when(encryptionService.decrypt("E")).thenReturn("s");

        var first = repository.findByRegistrationId("google");
        var second = repository.findByRegistrationId("google");
        assertThat(second).isSameAs(first);

        repository.onConfigUpdated(new OAuth2ConfigUpdatedEvent(orgId, OAuth2ProviderType.GOOGLE, true));

        var third = repository.findByRegistrationId("google");
        assertThat(third).isNotSameAs(first);
    }

    @Test
    void evictsOnDeletedEvent() {
        var entity = seeded(OAuth2ProviderType.GITLAB);
        entity.setClientId("c");
        entity.setClientSecretEncrypted("E");
        entity.setActive(true);
        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(configRepository.findByOrganizationIdAndProvider(orgId, OAuth2ProviderType.GITLAB))
                .thenReturn(Optional.of(entity));
        when(encryptionService.decrypt("E")).thenReturn("s");

        repository.findByRegistrationId("gitlab");
        repository.onConfigDeleted(new OAuth2ConfigDeletedEvent(orgId, OAuth2ProviderType.GITLAB));

        var rebuilt = repository.findByRegistrationId("gitlab");
        assertThat(rebuilt).isNotNull();
    }

    private OAuth2ConfigEntity seeded(OAuth2ProviderType provider) {
        var entity = new OAuth2ConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setProvider(provider);
        return entity;
    }
}
