package com.bablsoft.accessflow.mcp.internal.service;

import com.bablsoft.accessflow.mcp.api.ApiKeyDuplicateNameException;
import com.bablsoft.accessflow.mcp.api.ApiKeyNotFoundException;
import com.bablsoft.accessflow.mcp.api.ApiKeyService;
import com.bablsoft.accessflow.mcp.api.ApiKeyView;
import com.bablsoft.accessflow.mcp.api.IssuedApiKey;
import com.bablsoft.accessflow.mcp.internal.auth.ApiKeyHasher;
import com.bablsoft.accessflow.mcp.internal.persistence.entity.ApiKeyEntity;
import com.bablsoft.accessflow.mcp.internal.persistence.repo.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DefaultApiKeyService implements ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;

    @Override
    @Transactional
    public IssuedApiKey issue(UUID userId, UUID organizationId, String name, Instant expiresAt) {
        if (apiKeyRepository.existsByUserIdAndName(userId, name)) {
            throw new ApiKeyDuplicateNameException(name);
        }
        var rawKey = ApiKeyHasher.generate();
        var entity = new ApiKeyEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(userId);
        entity.setOrganizationId(organizationId);
        entity.setName(name);
        entity.setKeyPrefix(ApiKeyHasher.prefixOf(rawKey));
        entity.setKeyHash(ApiKeyHasher.hash(rawKey));
        entity.setExpiresAt(expiresAt);
        entity.setCreatedAt(Instant.now());
        var saved = apiKeyRepository.save(entity);
        return new IssuedApiKey(toView(saved), rawKey);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiKeyView> list(UUID userId) {
        return apiKeyRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(DefaultApiKeyService::toView)
                .toList();
    }

    @Override
    @Transactional
    public void revoke(UUID userId, UUID keyId) {
        var entity = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new ApiKeyNotFoundException(keyId));
        if (!entity.getUserId().equals(userId)) {
            // Don't leak existence of another user's key — treat as not found.
            throw new ApiKeyNotFoundException(keyId);
        }
        if (entity.getRevokedAt() == null) {
            entity.setRevokedAt(Instant.now());
            apiKeyRepository.save(entity);
        }
    }

    @Override
    @Transactional
    public Optional<UUID> resolveUserId(String rawKey) {
        if (!ApiKeyHasher.hasExpectedShape(rawKey)) {
            return Optional.empty();
        }
        var hash = ApiKeyHasher.hash(rawKey);
        var found = apiKeyRepository.findByKeyHash(hash);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        var entity = found.get();
        var now = Instant.now();
        if (entity.getRevokedAt() != null) {
            return Optional.empty();
        }
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(now)) {
            return Optional.empty();
        }
        try {
            apiKeyRepository.touchLastUsedAt(entity.getId(), now);
        } catch (RuntimeException ex) {
            log.warn("Failed to touch last_used_at for api key {}: {}", entity.getId(), ex.getMessage());
        }
        return Optional.of(entity.getUserId());
    }

    static ApiKeyView toView(ApiKeyEntity entity) {
        return new ApiKeyView(
                entity.getId(),
                entity.getUserId(),
                entity.getOrganizationId(),
                entity.getName(),
                entity.getKeyPrefix(),
                entity.getCreatedAt(),
                entity.getLastUsedAt(),
                entity.getExpiresAt(),
                entity.getRevokedAt()
        );
    }
}
