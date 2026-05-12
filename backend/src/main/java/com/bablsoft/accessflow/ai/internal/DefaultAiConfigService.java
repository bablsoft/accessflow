package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiConfigInUseException;
import com.bablsoft.accessflow.ai.api.AiConfigNameAlreadyExistsException;
import com.bablsoft.accessflow.ai.api.AiConfigNotFoundException;
import com.bablsoft.accessflow.ai.api.AiConfigService;
import com.bablsoft.accessflow.ai.api.AiConfigView;
import com.bablsoft.accessflow.ai.api.CreateAiConfigCommand;
import com.bablsoft.accessflow.ai.api.UpdateAiConfigCommand;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultAiConfigService implements AiConfigService {

    private final AiConfigRepository repository;
    private final CredentialEncryptionService encryptionService;
    private final DatasourceLookupService datasourceLookupService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(readOnly = true)
    public List<AiConfigView> list(UUID organizationId) {
        var entities = repository.findAllByOrganizationIdOrderByNameAsc(organizationId);
        if (entities.isEmpty()) {
            return List.of();
        }
        var ids = new HashSet<UUID>(entities.size());
        for (var e : entities) {
            ids.add(e.getId());
        }
        var counts = datasourceLookupService.countsByAiConfigIds(ids);
        return entities.stream()
                .map(e -> toView(e, counts.getOrDefault(e.getId(), 0)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AiConfigView get(UUID id, UUID organizationId) {
        var entity = loadInOrganization(id, organizationId);
        var inUse = datasourceLookupService.countsByAiConfigIds(Set.of(entity.getId()))
                .getOrDefault(entity.getId(), 0);
        return toView(entity, inUse);
    }

    @Override
    @Transactional
    public AiConfigView create(UUID organizationId, CreateAiConfigCommand command) {
        var trimmedName = trim(command.name());
        if (trimmedName == null || trimmedName.isBlank()) {
            throw new IllegalArgumentException("AI config name is required");
        }
        if (repository.existsByOrganizationIdAndNameIgnoreCase(organizationId, trimmedName)) {
            throw new AiConfigNameAlreadyExistsException(trimmedName);
        }
        var entity = new AiConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setName(trimmedName);
        entity.setProvider(command.provider());
        entity.setModel(command.model());
        entity.setEndpoint(blankToNull(command.endpoint()));
        if (command.apiKey() != null && !command.apiKey().isBlank()) {
            entity.setApiKeyEncrypted(encryptionService.encrypt(command.apiKey()));
        }
        if (command.timeoutMs() != null) {
            entity.setTimeoutMs(command.timeoutMs());
        }
        if (command.maxPromptTokens() != null) {
            entity.setMaxPromptTokens(command.maxPromptTokens());
        }
        if (command.maxCompletionTokens() != null) {
            entity.setMaxCompletionTokens(command.maxCompletionTokens());
        }
        var now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        var saved = repository.save(entity);
        return toView(saved, 0);
    }

    @Override
    @Transactional
    public AiConfigView update(UUID id, UUID organizationId, UpdateAiConfigCommand command) {
        var entity = loadInOrganization(id, organizationId);
        var oldProvider = entity.getProvider();
        var oldModel = entity.getModel();
        var oldCiphertext = entity.getApiKeyEncrypted();
        if (command.name() != null) {
            var trimmedName = trim(command.name());
            if (trimmedName == null || trimmedName.isBlank()) {
                throw new IllegalArgumentException("AI config name must not be blank");
            }
            if (!trimmedName.equalsIgnoreCase(entity.getName())
                    && repository.existsByOrganizationIdAndNameIgnoreCaseAndIdNot(organizationId, trimmedName, id)) {
                throw new AiConfigNameAlreadyExistsException(trimmedName);
            }
            entity.setName(trimmedName);
        }
        if (command.provider() != null) {
            entity.setProvider(command.provider());
        }
        if (command.model() != null) {
            entity.setModel(command.model());
        }
        if (command.endpoint() != null) {
            entity.setEndpoint(blankToNull(command.endpoint()));
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
        entity.setUpdatedAt(Instant.now());
        var saved = repository.save(entity);
        var apiKeyChanged = !Objects.equals(oldCiphertext, saved.getApiKeyEncrypted());
        if (oldProvider != saved.getProvider()
                || !Objects.equals(oldModel, saved.getModel())
                || apiKeyChanged
                || hasConnectivityChange(command)) {
            eventPublisher.publishEvent(new AiConfigUpdatedEvent(
                    saved.getId(), oldProvider, saved.getProvider(),
                    oldModel, saved.getModel(), apiKeyChanged));
        }
        var inUse = datasourceLookupService.countsByAiConfigIds(Set.of(saved.getId()))
                .getOrDefault(saved.getId(), 0);
        return toView(saved, inUse);
    }

    @Override
    @Transactional
    public void delete(UUID id, UUID organizationId) {
        var entity = loadInOrganization(id, organizationId);
        var refs = datasourceLookupService.findRefsByAiConfigId(entity.getId());
        if (!refs.isEmpty()) {
            var converted = refs.stream()
                    .map(r -> new AiConfigInUseException.DatasourceRef(r.id(), r.name()))
                    .toList();
            throw new AiConfigInUseException(entity.getId(), converted);
        }
        repository.delete(entity);
        eventPublisher.publishEvent(new AiConfigDeletedEvent(entity.getId()));
    }

    private boolean hasConnectivityChange(UpdateAiConfigCommand command) {
        return command.endpoint() != null
                || command.timeoutMs() != null
                || command.maxCompletionTokens() != null;
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

    private AiConfigEntity loadInOrganization(UUID id, UUID organizationId) {
        return repository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new AiConfigNotFoundException(id));
    }

    private static AiConfigView toView(AiConfigEntity entity, int inUseCount) {
        return new AiConfigView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getName(),
                entity.getProvider(),
                entity.getModel(),
                entity.getEndpoint(),
                entity.getApiKeyEncrypted() != null && !entity.getApiKeyEncrypted().isBlank(),
                entity.getTimeoutMs(),
                entity.getMaxPromptTokens(),
                entity.getMaxCompletionTokens(),
                inUseCount,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        return value.isBlank() ? null : value;
    }
}
