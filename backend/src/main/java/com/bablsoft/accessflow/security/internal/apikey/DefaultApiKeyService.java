package com.bablsoft.accessflow.security.internal.apikey;

import com.bablsoft.accessflow.security.api.ApiKeyBootstrapDeclaredException;
import com.bablsoft.accessflow.security.api.ApiKeyDuplicateNameException;
import com.bablsoft.accessflow.security.api.ApiKeyNotFoundException;
import com.bablsoft.accessflow.security.api.ApiKeyService;
import com.bablsoft.accessflow.security.api.ApiKeyView;
import com.bablsoft.accessflow.security.api.IssuedApiKey;
import com.bablsoft.accessflow.security.api.ResolvedApiKey;
import com.bablsoft.accessflow.security.internal.persistence.entity.ApiKeyEntity;
import com.bablsoft.accessflow.security.internal.persistence.repo.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
    @Transactional
    public ApiKeyView importOrUpdate(UUID userId, UUID organizationId, String name, String rawKey,
                                     Instant expiresAt) {
        if (!ApiKeyHasher.hasExpectedShape(rawKey)) {
            throw new IllegalArgumentException(
                    "Imported API key must start with the '" + ApiKeyHasher.PREFIX + "' prefix");
        }
        var entity = apiKeyRepository.findByUserIdAndName(userId, name).orElseGet(() -> {
            var fresh = new ApiKeyEntity();
            fresh.setId(UUID.randomUUID());
            fresh.setUserId(userId);
            fresh.setName(name);
            fresh.setCreatedAt(Instant.now());
            return fresh;
        });
        entity.setOrganizationId(organizationId);
        entity.setKeyPrefix(ApiKeyHasher.prefixOf(rawKey));
        entity.setKeyHash(ApiKeyHasher.hash(rawKey));
        entity.setExpiresAt(expiresAt);
        // Re-importing a declared key reactivates it if a prior run had revoked it. This is why
        // revoke() refuses a declared key outright (#871): an admin revoke would otherwise be
        // silently undone here on the next changed reconcile.
        entity.setRevokedAt(null);
        entity.setBootstrapDeclared(true);
        var saved = apiKeyRepository.save(entity);
        // At most one declared key per account: a renamed api-key-name demotes the previous row.
        apiKeyRepository.clearBootstrapDeclaredForOtherKeys(userId, saved.getId());
        return toView(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiKeyView> list(UUID userId) {
        return apiKeyRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(DefaultApiKeyService::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<ApiKeyView>> listByUserIds(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return apiKeyRepository.findByUserIdInOrderByCreatedAtDesc(userIds).stream()
                .map(DefaultApiKeyService::toView)
                .collect(Collectors.groupingBy(ApiKeyView::userId));
    }

    @Override
    @Transactional
    public void revoke(UUID userId, UUID keyId) {
        var entity = loadOwned(userId, keyId);
        if (entity.isBootstrapDeclared()) {
            throw new ApiKeyBootstrapDeclaredException(keyId);
        }
        if (entity.getRevokedAt() == null) {
            entity.setRevokedAt(Instant.now());
            apiKeyRepository.save(entity);
        }
    }

    @Override
    @Transactional
    public void expireAt(UUID userId, UUID keyId, Instant expiresAt) {
        var entity = loadOwned(userId, keyId);
        // Same trap as revoke: importOrUpdate re-asserts expires_at from the spec on the next
        // changed reconcile, so an expiry set here on a declared key would be silently undone.
        if (entity.isBootstrapDeclared()) {
            throw new ApiKeyBootstrapDeclaredException(keyId);
        }
        entity.setExpiresAt(expiresAt);
        apiKeyRepository.save(entity);
    }

    private ApiKeyEntity loadOwned(UUID userId, UUID keyId) {
        var entity = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new ApiKeyNotFoundException(keyId));
        if (!entity.getUserId().equals(userId)) {
            // Don't leak existence of another user's key — treat as not found.
            throw new ApiKeyNotFoundException(keyId);
        }
        return entity;
    }

    @Override
    @Transactional
    public Optional<ResolvedApiKey> resolve(String rawKey) {
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
        return Optional.of(new ResolvedApiKey(entity.getId(), entity.getUserId()));
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
                entity.getRevokedAt(),
                entity.isBootstrapDeclared()
        );
    }
}
