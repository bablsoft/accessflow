package com.bablsoft.accessflow.bootstrap.internal;

import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.persistence.entity.BootstrapStateEntity;
import com.bablsoft.accessflow.bootstrap.internal.persistence.repo.BootstrapStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class BootstrapStateTracker {

    private final BootstrapStateRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public Optional<String> findFingerprint(UUID organizationId, BootstrapResourceType resourceType, UUID resourceId) {
        return repository
                .findByOrganizationIdAndResourceTypeAndResourceId(organizationId, resourceType.name(), resourceId)
                .map(BootstrapStateEntity::getSpecFingerprint);
    }

    @Transactional
    public void recordFingerprint(UUID organizationId,
                                  BootstrapResourceType resourceType,
                                  UUID resourceId,
                                  String fingerprint) {
        upsertRow(organizationId, resourceType, resourceId, fingerprint);
    }

    /**
     * Persists the new fingerprint and publishes the corresponding bootstrap event in the same
     * transaction. The publish happens INSIDE this @Transactional method so that the audit
     * module's {@code @ApplicationModuleListener} (AFTER_COMMIT phase) actually receives it —
     * events published from a non-transactional caller would otherwise be silently dropped.
     */
    @Transactional
    public void recordFingerprintAndPublish(UUID organizationId,
                                            BootstrapResourceType resourceType,
                                            UUID resourceId,
                                            String fingerprint,
                                            BootstrapResourceUpsertedEvent event) {
        upsertRow(organizationId, resourceType, resourceId, fingerprint);
        eventPublisher.publishEvent(event);
    }

    /**
     * Publishes a bootstrap event inside a transaction without persisting a fingerprint. Used by
     * reconcilers that don't track per-resource state (e.g. admin user creation only happens once
     * and is detected by email lookup, not fingerprint comparison).
     */
    @Transactional
    public void publishWithinTransaction(BootstrapResourceUpsertedEvent event) {
        eventPublisher.publishEvent(event);
    }

    private void upsertRow(UUID organizationId,
                           BootstrapResourceType resourceType,
                           UUID resourceId,
                           String fingerprint) {
        var existing = repository
                .findByOrganizationIdAndResourceTypeAndResourceId(organizationId, resourceType.name(), resourceId);
        var now = Instant.now();
        if (existing.isPresent()) {
            var row = existing.get();
            row.setSpecFingerprint(fingerprint);
            row.setUpdatedAt(now);
            repository.save(row);
            return;
        }
        var row = new BootstrapStateEntity();
        row.setId(UUID.randomUUID());
        row.setOrganizationId(organizationId);
        row.setResourceType(resourceType.name());
        row.setResourceId(resourceId);
        row.setSpecFingerprint(fingerprint);
        row.setUpdatedAt(now);
        repository.save(row);
    }
}
