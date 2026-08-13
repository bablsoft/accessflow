package com.bablsoft.accessflow.scim.internal;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.scim.api.ScimAttributeMapping;
import com.bablsoft.accessflow.scim.api.ScimConfigService;
import com.bablsoft.accessflow.scim.api.ScimConfigView;
import com.bablsoft.accessflow.scim.api.ScimInvalidMappingException;
import com.bablsoft.accessflow.scim.api.UpdateScimConfigCommand;
import com.bablsoft.accessflow.scim.internal.persistence.entity.ScimConfigEntity;
import com.bablsoft.accessflow.scim.internal.persistence.repo.ScimConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultScimConfigService implements ScimConfigService {

    private final ScimConfigRepository configRepository;

    @Override
    @Transactional(readOnly = true)
    public ScimConfigView get(UUID organizationId) {
        return configRepository.findByOrganizationId(organizationId)
                .map(DefaultScimConfigService::toView)
                .orElseGet(() -> defaultView(organizationId));
    }

    @Override
    @Transactional
    public ScimConfigView update(UUID organizationId, UpdateScimConfigCommand command) {
        validate(command);
        var entity = configRepository.findByOrganizationId(organizationId)
                .orElseGet(() -> {
                    var created = new ScimConfigEntity();
                    created.setId(UUID.randomUUID());
                    created.setOrganizationId(organizationId);
                    return created;
                });
        if (command.enabled() != null) {
            entity.setEnabled(command.enabled());
        }
        if (command.attrEmail() != null) {
            entity.setAttrEmail(command.attrEmail());
        }
        if (command.attrDisplayName() != null) {
            entity.setAttrDisplayName(command.attrDisplayName());
        }
        if (command.defaultRole() != null) {
            entity.setDefaultRole(command.defaultRole());
        }
        return toView(configRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEnabled(UUID organizationId) {
        return configRepository.existsByOrganizationIdAndEnabledTrue(organizationId);
    }

    private static void validate(UpdateScimConfigCommand command) {
        if (command.attrEmail() != null
                && !ScimAttributeMapping.EMAIL_SOURCES.contains(command.attrEmail())) {
            throw new ScimInvalidMappingException("attr_email", command.attrEmail());
        }
        if (command.attrDisplayName() != null
                && !ScimAttributeMapping.DISPLAY_NAME_SOURCES.contains(command.attrDisplayName())) {
            throw new ScimInvalidMappingException("attr_display_name", command.attrDisplayName());
        }
    }

    private static ScimConfigView toView(ScimConfigEntity entity) {
        return new ScimConfigView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.isEnabled(),
                entity.getAttrEmail(),
                entity.getAttrDisplayName(),
                entity.getDefaultRole(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static ScimConfigView defaultView(UUID organizationId) {
        return new ScimConfigView(
                null,
                organizationId,
                false,
                ScimAttributeMapping.DEFAULT_EMAIL_SOURCE,
                ScimAttributeMapping.DEFAULT_DISPLAY_NAME_SOURCE,
                UserRoleType.ANALYST,
                null,
                null);
    }
}
