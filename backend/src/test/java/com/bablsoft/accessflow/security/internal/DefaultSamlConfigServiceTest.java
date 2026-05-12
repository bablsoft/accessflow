package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.UpdateSamlConfigCommand;
import com.bablsoft.accessflow.security.internal.persistence.entity.SamlConfigEntity;
import com.bablsoft.accessflow.security.internal.persistence.repo.SamlConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultSamlConfigServiceTest {

    @Mock SamlConfigRepository repository;
    @Mock CredentialEncryptionService encryptionService;

    private DefaultSamlConfigService service;
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultSamlConfigService(repository, encryptionService);
    }

    @Test
    void getOrDefaultReturnsTransientDefaultsWhenNoRowExists() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.empty());

        var view = service.getOrDefault(orgId);

        assertThat(view.id()).isNull();
        assertThat(view.organizationId()).isEqualTo(orgId);
        assertThat(view.active()).isFalse();
        assertThat(view.attrEmail()).isEqualTo("email");
        assertThat(view.attrDisplayName()).isEqualTo("displayName");
        assertThat(view.defaultRole()).isEqualTo(UserRoleType.ANALYST);
        assertThat(view.signingCertConfigured()).isFalse();
    }

    @Test
    void updateUpsertsAndEncryptsCertOnChange() {
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.empty());
        when(repository.save(any(SamlConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(encryptionService.encrypt("CERT")).thenReturn("ENC(CERT)");

        var command = new UpdateSamlConfigCommand(
                "https://idp.example.com/metadata",
                "idp-entity",
                "sp-entity",
                "https://app.example.com/saml/acs",
                null,
                "CERT",
                "email",
                "displayName",
                "role",
                UserRoleType.REVIEWER,
                true);

        var view = service.update(orgId, command);

        assertThat(view.idpMetadataUrl()).isEqualTo("https://idp.example.com/metadata");
        assertThat(view.signingCertConfigured()).isTrue();
        assertThat(view.defaultRole()).isEqualTo(UserRoleType.REVIEWER);
        assertThat(view.active()).isTrue();
    }

    @Test
    void updateLeavesCertAloneWhenMaskedPlaceholderProvided() {
        var entity = seeded();
        entity.setSigningCertPem("ENC(prior)");
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(SamlConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(orgId, partialCert(UpdateSamlConfigCommand.MASKED_CERT));

        var captor = ArgumentCaptor.forClass(SamlConfigEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSigningCertPem()).isEqualTo("ENC(prior)");
        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    void updateClearsCertWhenBlankProvided() {
        var entity = seeded();
        entity.setSigningCertPem("ENC(prior)");
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(SamlConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.update(orgId, partialCert(""));

        var captor = ArgumentCaptor.forClass(SamlConfigEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSigningCertPem()).isNull();
    }

    @Test
    void updateLeavesAttributeMappingWhenBlankProvided() {
        var entity = seeded();
        when(repository.findByOrganizationId(orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(SamlConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var command = new UpdateSamlConfigCommand(null, null, null, null, null, null,
                "  ", "  ", null, null, null);
        var view = service.update(orgId, command);

        assertThat(view.attrEmail()).isEqualTo("email");
        assertThat(view.attrDisplayName()).isEqualTo("displayName");
    }

    private UpdateSamlConfigCommand partialCert(String cert) {
        return new UpdateSamlConfigCommand(null, null, null, null, null, cert,
                null, null, null, null, null);
    }

    private SamlConfigEntity seeded() {
        var entity = new SamlConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        return entity;
    }
}
