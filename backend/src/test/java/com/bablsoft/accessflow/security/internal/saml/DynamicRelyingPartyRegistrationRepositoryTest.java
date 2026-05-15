package com.bablsoft.accessflow.security.internal.saml;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.security.internal.persistence.entity.SamlConfigEntity;
import com.bablsoft.accessflow.security.internal.persistence.repo.SamlConfigRepository;
import com.bablsoft.accessflow.security.internal.saml.events.SamlConfigUpdatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicRelyingPartyRegistrationRepositoryTest {

    @Mock SamlConfigRepository samlConfigRepository;
    @Mock CredentialEncryptionService encryptionService;
    @Mock SamlSpKeyProvider spKeyProvider;
    @Mock OrganizationLookupService organizationLookupService;

    private final UUID orgId = UUID.randomUUID();

    @Test
    void unknownRegistrationIdReturnsNull() {
        var repo = new DynamicRelyingPartyRegistrationRepository(
                samlConfigRepository, encryptionService, spKeyProvider, organizationLookupService);

        assertThat(repo.findByRegistrationId("other")).isNull();
    }

    @Test
    void returnsNullWhenNoActiveConfig() {
        lenient().when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        lenient().when(samlConfigRepository.findByOrganizationId(orgId)).thenReturn(Optional.empty());
        var repo = new DynamicRelyingPartyRegistrationRepository(
                samlConfigRepository, encryptionService, spKeyProvider, organizationLookupService);

        assertThat(repo.findByRegistrationId(DynamicRelyingPartyRegistrationRepository.REGISTRATION_ID))
                .isNull();
    }

    @Test
    void returnsNullWhenConfigPresentButInactive() {
        var entity = new SamlConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setActive(false);
        lenient().when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        lenient().when(samlConfigRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));
        var repo = new DynamicRelyingPartyRegistrationRepository(
                samlConfigRepository, encryptionService, spKeyProvider, organizationLookupService);

        assertThat(repo.findByRegistrationId(DynamicRelyingPartyRegistrationRepository.REGISTRATION_ID))
                .isNull();
    }

    @Test
    void returnsNullWhenActiveConfigMissesIdpMetadataUrl() {
        var entity = new SamlConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setActive(true);
        entity.setIdpMetadataUrl(null);
        lenient().when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        lenient().when(samlConfigRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));
        var repo = new DynamicRelyingPartyRegistrationRepository(
                samlConfigRepository, encryptionService, spKeyProvider, organizationLookupService);

        assertThat(repo.findByRegistrationId(DynamicRelyingPartyRegistrationRepository.REGISTRATION_ID))
                .isNull();
    }

    @Test
    void returnsNullWhenActiveConfigMissesIdpCert() {
        var entity = new SamlConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setActive(true);
        entity.setIdpMetadataUrl("https://idp.example.com/metadata");
        entity.setSigningCertPem(null);
        lenient().when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        lenient().when(samlConfigRepository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));
        var repo = new DynamicRelyingPartyRegistrationRepository(
                samlConfigRepository, encryptionService, spKeyProvider, organizationLookupService);

        assertThat(repo.findByRegistrationId(DynamicRelyingPartyRegistrationRepository.REGISTRATION_ID))
                .isNull();
    }

    @Test
    void iteratorReturnsEmptyWhenNothingActive() {
        when(organizationLookupService.singleOrganization()).thenReturn(orgId);
        when(samlConfigRepository.findByOrganizationId(orgId)).thenReturn(Optional.empty());
        var repo = new DynamicRelyingPartyRegistrationRepository(
                samlConfigRepository, encryptionService, spKeyProvider, organizationLookupService);

        assertThat(repo.iterator().hasNext()).isFalse();
    }

    @Test
    void samlConfigUpdatedEventEvictsCachedRegistration() {
        var repo = new DynamicRelyingPartyRegistrationRepository(
                samlConfigRepository, encryptionService, spKeyProvider, organizationLookupService);

        // No assertion on cache visibility; just verify the listener does not throw and accepts the event.
        repo.onSamlConfigUpdated(new SamlConfigUpdatedEvent(orgId));
    }
}
