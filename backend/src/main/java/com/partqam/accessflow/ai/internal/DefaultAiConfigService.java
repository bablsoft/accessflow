package com.partqam.accessflow.ai.internal;

import com.partqam.accessflow.ai.api.AiConfigService;
import com.partqam.accessflow.ai.api.AiConfigView;
import com.partqam.accessflow.ai.api.UpdateAiConfigCommand;
import com.partqam.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.partqam.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.partqam.accessflow.core.api.AiProviderType;
import com.partqam.accessflow.core.api.CredentialEncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultAiConfigService implements AiConfigService {

    private final AiConfigRepository repository;
    private final CredentialEncryptionService encryptionService;
    private final AiAnalyzerProperties analyzerProperties;

    @Override
    @Transactional(readOnly = true)
    public AiConfigView getOrDefault(UUID organizationId) {
        return repository.findByOrganizationId(organizationId)
                .map(this::toView)
                .orElseGet(() -> defaultView(organizationId));
    }

    @Override
    @Transactional
    public AiConfigView update(UUID organizationId, UpdateAiConfigCommand command) {
        var entity = repository.findByOrganizationId(organizationId)
                .orElseGet(() -> seed(organizationId));
        if (command.provider() != null) {
            entity.setProvider(command.provider());
        }
        if (command.model() != null) {
            entity.setModel(command.model());
        }
        if (command.endpoint() != null) {
            entity.setEndpoint(command.endpoint().isBlank() ? null : command.endpoint());
        }
        applyApiKey(entity, command.apiKey());
        if (command.timeoutMs() != null) {
            entity.setTimeoutMs(command.timeoutMs());
        }
        if (command.maxPromptTokens() != null) {
            entity.setMaxPromptTokens(command.maxPromptTokens());
        }
        if (command.maxCompletionTokens() != null) {
            entity.setMaxCompletionTokens(command.maxCompletionTokens());
        }
        if (command.enableAiDefault() != null) {
            entity.setEnableAiDefault(command.enableAiDefault());
        }
        if (command.autoApproveLow() != null) {
            entity.setAutoApproveLow(command.autoApproveLow());
        }
        if (command.blockCritical() != null) {
            entity.setBlockCritical(command.blockCritical());
        }
        if (command.includeSchema() != null) {
            entity.setIncludeSchema(command.includeSchema());
        }
        entity.setUpdatedAt(Instant.now());
        return toView(repository.save(entity));
    }

    private void applyApiKey(AiConfigEntity entity, String submitted) {
        if (submitted == null || UpdateAiConfigCommand.MASKED_API_KEY.equals(submitted)) {
            return;
        }
        if (submitted.isBlank()) {
            entity.setApiKeyEncrypted(null);
            return;
        }
        entity.setApiKeyEncrypted(encryptionService.encrypt(submitted));
    }

    private AiConfigEntity seed(UUID organizationId) {
        var entity = new AiConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        var defaults = defaultsFor(analyzerProperties.provider());
        entity.setProvider(defaults.provider());
        entity.setModel(defaults.model());
        entity.setEndpoint(defaults.endpoint());
        return entity;
    }

    private AiConfigView defaultView(UUID organizationId) {
        var defaults = defaultsFor(analyzerProperties.provider());
        var now = Instant.now();
        return new AiConfigView(
                null,
                organizationId,
                defaults.provider(),
                defaults.model(),
                defaults.endpoint(),
                false,
                30_000,
                8_000,
                2_000,
                true,
                false,
                true,
                true,
                now,
                now);
    }

    private AiConfigView toView(AiConfigEntity entity) {
        return new AiConfigView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getProvider(),
                entity.getModel(),
                entity.getEndpoint(),
                entity.getApiKeyEncrypted() != null && !entity.getApiKeyEncrypted().isBlank(),
                entity.getTimeoutMs(),
                entity.getMaxPromptTokens(),
                entity.getMaxCompletionTokens(),
                entity.isEnableAiDefault(),
                entity.isAutoApproveLow(),
                entity.isBlockCritical(),
                entity.isIncludeSchema(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static ProviderDefaults defaultsFor(AiProviderType provider) {
        return switch (provider) {
            case OPENAI -> new ProviderDefaults(AiProviderType.OPENAI, "gpt-4o", "https://api.openai.com/v1");
            case ANTHROPIC -> new ProviderDefaults(AiProviderType.ANTHROPIC, "claude-sonnet-4-20250514",
                    "https://api.anthropic.com/v1");
            case OLLAMA -> new ProviderDefaults(AiProviderType.OLLAMA, "llama3.1:70b", "http://localhost:11434/api");
        };
    }

    private record ProviderDefaults(AiProviderType provider, String model, String endpoint) {
    }
}
