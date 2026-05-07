package com.partqam.accessflow.security.internal;

import com.partqam.accessflow.core.api.CredentialEncryptionService;
import com.partqam.accessflow.security.api.SamlConfigService;
import com.partqam.accessflow.security.api.SamlConfigView;
import com.partqam.accessflow.security.api.UpdateSamlConfigCommand;
import com.partqam.accessflow.security.internal.persistence.entity.SamlConfigEntity;
import com.partqam.accessflow.security.internal.persistence.repo.SamlConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "accessflow.edition", havingValue = "enterprise")
@RequiredArgsConstructor
class DefaultSamlConfigService implements SamlConfigService {

    private final SamlConfigRepository repository;
    private final CredentialEncryptionService encryptionService;

    @Override
    @Transactional(readOnly = true)
    public SamlConfigView getOrDefault(UUID organizationId) {
        return repository.findByOrganizationId(organizationId)
                .map(this::toView)
                .orElseGet(() -> defaultView(organizationId));
    }

    @Override
    @Transactional
    public SamlConfigView update(UUID organizationId, UpdateSamlConfigCommand command) {
        var entity = repository.findByOrganizationId(organizationId)
                .orElseGet(() -> seed(organizationId));
        if (command.idpMetadataUrl() != null) {
            entity.setIdpMetadataUrl(blankToNull(command.idpMetadataUrl()));
        }
        if (command.idpEntityId() != null) {
            entity.setIdpEntityId(blankToNull(command.idpEntityId()));
        }
        if (command.spEntityId() != null) {
            entity.setSpEntityId(blankToNull(command.spEntityId()));
        }
        if (command.acsUrl() != null) {
            entity.setAcsUrl(blankToNull(command.acsUrl()));
        }
        if (command.sloUrl() != null) {
            entity.setSloUrl(blankToNull(command.sloUrl()));
        }
        applySigningCert(entity, command.signingCertPem());
        if (command.attrEmail() != null && !command.attrEmail().isBlank()) {
            entity.setAttrEmail(command.attrEmail().trim());
        }
        if (command.attrDisplayName() != null && !command.attrDisplayName().isBlank()) {
            entity.setAttrDisplayName(command.attrDisplayName().trim());
        }
        if (command.attrRole() != null) {
            entity.setAttrRole(blankToNull(command.attrRole()));
        }
        if (command.defaultRole() != null) {
            entity.setDefaultRole(command.defaultRole());
        }
        if (command.active() != null) {
            entity.setActive(command.active());
        }
        entity.setUpdatedAt(Instant.now());
        return toView(repository.save(entity));
    }

    private void applySigningCert(SamlConfigEntity entity, String submitted) {
        if (submitted == null || UpdateSamlConfigCommand.MASKED_CERT.equals(submitted)) {
            return;
        }
        if (submitted.isBlank()) {
            entity.setSigningCertPem(null);
            return;
        }
        entity.setSigningCertPem(encryptionService.encrypt(submitted));
    }

    private SamlConfigEntity seed(UUID organizationId) {
        var entity = new SamlConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        return entity;
    }

    private SamlConfigView defaultView(UUID organizationId) {
        var defaults = new SamlConfigEntity();
        var now = Instant.now();
        return new SamlConfigView(
                null,
                organizationId,
                null,
                null,
                null,
                null,
                null,
                false,
                defaults.getAttrEmail(),
                defaults.getAttrDisplayName(),
                null,
                defaults.getDefaultRole(),
                false,
                now,
                now);
    }

    private SamlConfigView toView(SamlConfigEntity entity) {
        return new SamlConfigView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getIdpMetadataUrl(),
                entity.getIdpEntityId(),
                entity.getSpEntityId(),
                entity.getAcsUrl(),
                entity.getSloUrl(),
                entity.getSigningCertPem() != null && !entity.getSigningCertPem().isBlank(),
                entity.getAttrEmail(),
                entity.getAttrDisplayName(),
                entity.getAttrRole(),
                entity.getDefaultRole(),
                entity.isActive(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static String blankToNull(String s) {
        return s.isBlank() ? null : s.trim();
    }
}
