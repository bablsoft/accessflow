package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.internal.config.LangfuseProperties;
import com.bablsoft.accessflow.ai.internal.persistence.entity.LangfuseConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.LangfuseConfigRepository;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads, decrypts and caches the effective Langfuse settings per organization. Returns empty when
 * Langfuse is disabled or the credentials are incomplete, so callers ({@link DefaultLangfuseTracer},
 * {@link DefaultLangfusePromptProvider}) can short-circuit. Cache entries are evicted on
 * {@link LangfuseConfigUpdatedEvent} after the originating transaction commits.
 */
@Component
@RequiredArgsConstructor
class LangfuseConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(LangfuseConfigResolver.class);

    private final LangfuseConfigRepository repository;
    private final CredentialEncryptionService encryptionService;
    private final LangfuseProperties properties;

    private final ConcurrentHashMap<UUID, Optional<ResolvedLangfuseConfig>> cache = new ConcurrentHashMap<>();

    Optional<ResolvedLangfuseConfig> resolve(UUID organizationId) {
        if (organizationId == null) {
            return Optional.empty();
        }
        return cache.computeIfAbsent(organizationId, this::load);
    }

    private Optional<ResolvedLangfuseConfig> load(UUID organizationId) {
        var entity = repository.findByOrganizationId(organizationId).orElse(null);
        if (entity == null || !entity.isEnabled()) {
            return Optional.empty();
        }
        if (isBlank(entity.getPublicKey()) || isBlank(entity.getSecretKeyEncrypted())) {
            log.debug("Langfuse enabled for org {} but credentials incomplete; skipping", organizationId);
            return Optional.empty();
        }
        var host = resolveHost(entity);
        var secretKey = encryptionService.decrypt(entity.getSecretKeyEncrypted());
        return Optional.of(new ResolvedLangfuseConfig(
                host,
                entity.getPublicKey(),
                secretKey,
                entity.isTracingEnabled(),
                entity.isPromptManagementEnabled()));
    }

    private String resolveHost(LangfuseConfigEntity entity) {
        var raw = isBlank(entity.getHost())
                ? properties.defaultHost().toString()
                : entity.getHost().trim();
        return raw.endsWith("/") ? raw : raw + "/";
    }

    URI defaultHost() {
        return properties.defaultHost();
    }

    @ApplicationModuleListener
    void onConfigUpdated(LangfuseConfigUpdatedEvent event) {
        cache.remove(event.organizationId());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
