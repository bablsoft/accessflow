package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.notifications.api.SlackAppConfigNotFoundException;
import com.bablsoft.accessflow.notifications.api.SlackAppConfigService;
import com.bablsoft.accessflow.notifications.api.SlackAppConfigValidationException;
import com.bablsoft.accessflow.notifications.api.SlackAppConfigView;
import com.bablsoft.accessflow.notifications.api.UpsertSlackAppConfigCommand;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.SlackAppConfigEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.SlackAppConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefaultSlackAppConfigService implements SlackAppConfigService {

    private final SlackAppConfigRepository repository;
    private final CredentialEncryptionService encryptionService;

    @Override
    @Transactional(readOnly = true)
    public Optional<SlackAppConfigView> get(UUID organizationId) {
        return repository.findByOrganizationId(organizationId).map(DefaultSlackAppConfigService::toView);
    }

    @Override
    @Transactional
    public SlackAppConfigView upsert(UUID organizationId, UpsertSlackAppConfigCommand command) {
        var appId = trim(command.appId());
        var defaultChannelId = trim(command.defaultChannelId());
        if (appId == null || appId.isBlank()) {
            throw new SlackAppConfigValidationException("Slack app id is required");
        }
        if (defaultChannelId == null || defaultChannelId.isBlank()) {
            throw new SlackAppConfigValidationException("Slack default channel id is required");
        }
        var entity = repository.findByOrganizationId(organizationId).orElse(null);
        var creating = entity == null;
        if (creating) {
            entity = new SlackAppConfigEntity();
            entity.setId(UUID.randomUUID());
            entity.setOrganizationId(organizationId);
            entity.setCreatedAt(Instant.now());
        }
        entity.setAppId(appId);
        entity.setDefaultChannelId(defaultChannelId);
        applySecret(command.botToken(), entity::setBotTokenEncrypted, entity.getBotTokenEncrypted(),
                creating, "Slack bot token is required");
        applySecret(command.signingSecret(), entity::setSigningSecretEncrypted, entity.getSigningSecretEncrypted(),
                creating, "Slack signing secret is required");
        if (command.active() != null) {
            entity.setActive(command.active());
        }
        entity.setUpdatedAt(Instant.now());
        return toView(repository.save(entity));
    }

    @Override
    @Transactional
    public void delete(UUID organizationId) {
        var entity = repository.findByOrganizationId(organizationId)
                .orElseThrow(() -> new SlackAppConfigNotFoundException(organizationId));
        repository.delete(entity);
    }

    /** Active Slack app for the org with secrets decrypted — internal runtime use only. */
    @Transactional(readOnly = true)
    public Optional<DecryptedSlackApp> findActiveByOrg(UUID organizationId) {
        return repository.findByOrganizationId(organizationId)
                .filter(SlackAppConfigEntity::isActive)
                .map(this::toDecrypted);
    }

    /** Slack app for the org with secrets decrypted, regardless of active flag — used by "send test". */
    @Transactional(readOnly = true)
    public Optional<DecryptedSlackApp> findDecryptedByOrg(UUID organizationId) {
        return repository.findByOrganizationId(organizationId).map(this::toDecrypted);
    }

    /** Active Slack app matching the inbound Slack {@code api_app_id}. */
    @Transactional(readOnly = true)
    public Optional<DecryptedSlackApp> findActiveByAppId(String appId) {
        if (appId == null || appId.isBlank()) {
            return Optional.empty();
        }
        return repository.findByAppId(appId)
                .filter(SlackAppConfigEntity::isActive)
                .map(this::toDecrypted);
    }

    private DecryptedSlackApp toDecrypted(SlackAppConfigEntity entity) {
        return new DecryptedSlackApp(
                entity.getOrganizationId(),
                entity.getAppId(),
                encryptionService.decrypt(entity.getBotTokenEncrypted()),
                encryptionService.decrypt(entity.getSigningSecretEncrypted()),
                entity.getDefaultChannelId());
    }

    private void applySecret(String submitted, java.util.function.Consumer<String> setter,
                             String existingCipher, boolean creating, String requiredMessage) {
        if (submitted == null || UpsertSlackAppConfigCommand.MASKED.equals(submitted)) {
            if (creating) {
                throw new SlackAppConfigValidationException(requiredMessage);
            }
            return;
        }
        if (submitted.isBlank()) {
            if (creating || existingCipher == null) {
                throw new SlackAppConfigValidationException(requiredMessage);
            }
            return;
        }
        setter.accept(encryptionService.encrypt(submitted));
    }

    private static SlackAppConfigView toView(SlackAppConfigEntity entity) {
        return new SlackAppConfigView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getAppId(),
                entity.getDefaultChannelId(),
                entity.isActive(),
                entity.getBotTokenEncrypted() != null && !entity.getBotTokenEncrypted().isBlank(),
                entity.getSigningSecretEncrypted() != null && !entity.getSigningSecretEncrypted().isBlank(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
