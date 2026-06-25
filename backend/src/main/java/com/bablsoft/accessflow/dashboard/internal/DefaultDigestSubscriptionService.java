package com.bablsoft.accessflow.dashboard.internal;

import com.bablsoft.accessflow.dashboard.api.DigestSubscriptionService;
import com.bablsoft.accessflow.dashboard.api.DigestSubscriptionView;
import com.bablsoft.accessflow.dashboard.internal.persistence.entity.DashboardDigestSubscriptionEntity;
import com.bablsoft.accessflow.dashboard.internal.persistence.repo.DashboardDigestSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** Persists and reads a user's weekly-digest opt-in (AF-498). One row per user. Self-scoped. */
@Service
@RequiredArgsConstructor
class DefaultDigestSubscriptionService implements DigestSubscriptionService {

    private final DashboardDigestSubscriptionRepository repository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public DigestSubscriptionView get(UUID organizationId, UUID userId) {
        return repository.findByUserId(userId)
                .map(e -> new DigestSubscriptionView(e.isEnabled(), e.getLastSentAt()))
                .orElseGet(() -> new DigestSubscriptionView(false, null));
    }

    @Override
    @Transactional
    public DigestSubscriptionView set(UUID organizationId, UUID userId, boolean enabled) {
        if (organizationId == null || userId == null) {
            throw new IllegalArgumentException("organizationId and userId are required");
        }
        var now = clock.instant();
        var entity = repository.findByUserId(userId).orElse(null);
        if (entity == null) {
            entity = new DashboardDigestSubscriptionEntity();
            entity.setId(UUID.randomUUID());
            entity.setUserId(userId);
            entity.setOrganizationId(organizationId);
            entity.setCreatedAt(now);
        }
        entity.setEnabled(enabled);
        entity.setUpdatedAt(now);
        var saved = repository.save(entity);
        return new DigestSubscriptionView(saved.isEnabled(), saved.getLastSentAt());
    }
}
