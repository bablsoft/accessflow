package com.bablsoft.accessflow.scim.internal;

import com.bablsoft.accessflow.scim.api.IssuedScimToken;
import com.bablsoft.accessflow.scim.api.ScimPrincipal;
import com.bablsoft.accessflow.scim.api.ScimTokenNameConflictException;
import com.bablsoft.accessflow.scim.api.ScimTokenNotFoundException;
import com.bablsoft.accessflow.scim.api.ScimTokenService;
import com.bablsoft.accessflow.scim.api.ScimTokenView;
import com.bablsoft.accessflow.scim.internal.persistence.entity.ScimTokenEntity;
import com.bablsoft.accessflow.scim.internal.persistence.repo.ScimTokenRepository;
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
class DefaultScimTokenService implements ScimTokenService {

    private final ScimTokenRepository tokenRepository;

    @Override
    @Transactional(readOnly = true)
    public List<ScimTokenView> list(UUID organizationId) {
        return tokenRepository.findAllByOrganizationIdOrderByCreatedAtDesc(organizationId).stream()
                .map(DefaultScimTokenService::toView)
                .toList();
    }

    @Override
    @Transactional
    public IssuedScimToken create(UUID organizationId, String name, UUID createdBy) {
        var trimmed = name == null ? null : name.trim();
        if (tokenRepository.existsByOrganizationIdAndName(organizationId, trimmed)) {
            throw new ScimTokenNameConflictException(trimmed);
        }
        var rawToken = ScimTokenHasher.generate();
        var entity = new ScimTokenEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setName(trimmed);
        entity.setTokenPrefix(ScimTokenHasher.prefixOf(rawToken));
        entity.setTokenHash(ScimTokenHasher.hash(rawToken));
        entity.setCreatedBy(createdBy);
        return new IssuedScimToken(toView(tokenRepository.save(entity)), rawToken);
    }

    @Override
    @Transactional
    public void revoke(UUID organizationId, UUID tokenId) {
        var entity = tokenRepository.findByOrganizationIdAndId(organizationId, tokenId)
                .orElseThrow(() -> new ScimTokenNotFoundException(tokenId));
        if (entity.getRevokedAt() == null) {
            entity.setRevokedAt(Instant.now());
        }
    }

    /**
     * Deliberately NOT {@code @Transactional}: with a managed entity the last-used bump would
     * flush at commit, outside the try/catch, and a failure there would escape as an unhandled
     * exception from the auth filter. Detached read + explicit save keeps the bump truly
     * best-effort.
     */
    @Override
    public Optional<ScimPrincipal> authenticate(String rawToken) {
        if (!ScimTokenHasher.hasExpectedShape(rawToken)) {
            return Optional.empty();
        }
        var entity = tokenRepository.findByTokenHash(ScimTokenHasher.hash(rawToken)).orElse(null);
        if (entity == null || entity.getRevokedAt() != null) {
            return Optional.empty();
        }
        touchLastUsedAt(entity);
        return Optional.of(new ScimPrincipal(
                entity.getOrganizationId(), entity.getId(), entity.getName()));
    }

    private void touchLastUsedAt(ScimTokenEntity entity) {
        try {
            entity.setLastUsedAt(Instant.now());
            tokenRepository.save(entity);
        } catch (RuntimeException ex) {
            // Best-effort freshness marker — never fail authentication over it.
            log.debug("Failed to bump last_used_at for SCIM token {}", entity.getId(), ex);
        }
    }

    private static ScimTokenView toView(ScimTokenEntity entity) {
        return new ScimTokenView(
                entity.getId(),
                entity.getName(),
                entity.getTokenPrefix(),
                entity.getCreatedAt(),
                entity.getLastUsedAt(),
                entity.getRevokedAt());
    }
}
