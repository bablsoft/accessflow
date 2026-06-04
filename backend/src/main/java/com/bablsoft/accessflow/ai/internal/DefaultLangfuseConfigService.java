package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.LangfuseConfigService;
import com.bablsoft.accessflow.ai.api.LangfuseConfigView;
import com.bablsoft.accessflow.ai.api.LangfuseConnectionTestResult;
import com.bablsoft.accessflow.ai.api.UpdateLangfuseConfigCommand;
import com.bablsoft.accessflow.ai.internal.persistence.entity.LangfuseConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.LangfuseConfigRepository;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultLangfuseConfigService implements LangfuseConfigService {

    private static final Logger log = LoggerFactory.getLogger(DefaultLangfuseConfigService.class);

    private final LangfuseConfigRepository repository;
    private final CredentialEncryptionService encryptionService;
    private final ApplicationEventPublisher eventPublisher;
    private final LangfuseConfigResolver configResolver;
    private final LangfuseClient client;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public LangfuseConfigView getOrDefault(UUID organizationId) {
        return repository.findByOrganizationId(organizationId)
                .map(DefaultLangfuseConfigService::toView)
                .orElseGet(() -> defaultView(organizationId));
    }

    @Override
    @Transactional
    public LangfuseConfigView update(UUID organizationId, UpdateLangfuseConfigCommand command) {
        var entity = repository.findByOrganizationId(organizationId)
                .orElseGet(() -> seed(organizationId));
        if (command.enabled() != null) {
            entity.setEnabled(command.enabled());
        }
        if (command.host() != null) {
            entity.setHost(blankToNull(command.host()));
        }
        if (command.publicKey() != null) {
            entity.setPublicKey(blankToNull(command.publicKey()));
        }
        applySecretKey(entity, command.secretKey());
        if (command.tracingEnabled() != null) {
            entity.setTracingEnabled(command.tracingEnabled());
        }
        if (command.promptManagementEnabled() != null) {
            entity.setPromptManagementEnabled(command.promptManagementEnabled());
        }
        entity.setUpdatedAt(Instant.now());
        var saved = repository.save(entity);
        eventPublisher.publishEvent(new LangfuseConfigUpdatedEvent(organizationId));
        return toView(saved);
    }

    @Override
    public LangfuseConnectionTestResult testConnection(UUID organizationId) {
        var resolved = configResolver.resolve(organizationId).orElse(null);
        if (resolved == null) {
            return new LangfuseConnectionTestResult(false, message("langfuse.test.not_configured"));
        }
        try {
            client.verifyConnection(resolved);
            return new LangfuseConnectionTestResult(true, message("langfuse.test.success"));
        } catch (RuntimeException ex) {
            log.warn("Langfuse connection test failed for org {}: {}", organizationId, ex.getMessage());
            return new LangfuseConnectionTestResult(false, ex.getMessage());
        }
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    private void applySecretKey(LangfuseConfigEntity entity, String submitted) {
        if (submitted == null || UpdateLangfuseConfigCommand.MASKED_SECRET.equals(submitted)) {
            return;
        }
        if (submitted.isBlank()) {
            entity.setSecretKeyEncrypted(null);
            return;
        }
        entity.setSecretKeyEncrypted(encryptionService.encrypt(submitted));
    }

    private LangfuseConfigEntity seed(UUID organizationId) {
        var entity = new LangfuseConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        return entity;
    }

    private static LangfuseConfigView defaultView(UUID organizationId) {
        var defaults = new LangfuseConfigEntity();
        var now = Instant.now();
        return new LangfuseConfigView(
                null,
                organizationId,
                defaults.isEnabled(),
                null,
                null,
                false,
                defaults.isTracingEnabled(),
                defaults.isPromptManagementEnabled(),
                now,
                now);
    }

    private static LangfuseConfigView toView(LangfuseConfigEntity entity) {
        return new LangfuseConfigView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.isEnabled(),
                entity.getHost(),
                entity.getPublicKey(),
                entity.getSecretKeyEncrypted() != null && !entity.getSecretKeyEncrypted().isBlank(),
                entity.isTracingEnabled(),
                entity.isPromptManagementEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        return value.isBlank() ? null : value.trim();
    }
}
