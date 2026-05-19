package com.bablsoft.accessflow.bootstrap.internal;

import com.bablsoft.accessflow.audit.events.BootstrapChangeKind;
import com.bablsoft.accessflow.audit.events.BootstrapResourceType;
import com.bablsoft.accessflow.audit.events.BootstrapResourceUpsertedEvent;
import com.bablsoft.accessflow.bootstrap.internal.persistence.entity.BootstrapStateEntity;
import com.bablsoft.accessflow.bootstrap.internal.persistence.repo.BootstrapStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BootstrapStateTrackerTest {

    @Mock BootstrapStateRepository repository;
    @Mock ApplicationEventPublisher eventPublisher;
    BootstrapStateTracker tracker;

    private final UUID orgId = UUID.randomUUID();
    private final UUID resourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tracker = new BootstrapStateTracker(repository, eventPublisher);
    }

    @Test
    void findFingerprintReturnsStoredValue() {
        var entity = new BootstrapStateEntity();
        entity.setSpecFingerprint("abc123");
        when(repository.findByOrganizationIdAndResourceTypeAndResourceId(
                orgId, BootstrapResourceType.DATASOURCE.name(), resourceId))
                .thenReturn(Optional.of(entity));

        assertThat(tracker.findFingerprint(orgId, BootstrapResourceType.DATASOURCE, resourceId))
                .contains("abc123");
    }

    @Test
    void findFingerprintReturnsEmptyWhenAbsent() {
        when(repository.findByOrganizationIdAndResourceTypeAndResourceId(
                any(), any(), any())).thenReturn(Optional.empty());

        assertThat(tracker.findFingerprint(orgId, BootstrapResourceType.DATASOURCE, resourceId))
                .isEmpty();
    }

    @Test
    void recordFingerprintInsertsWhenAbsent() {
        when(repository.findByOrganizationIdAndResourceTypeAndResourceId(
                any(), any(), any())).thenReturn(Optional.empty());

        tracker.recordFingerprint(orgId, BootstrapResourceType.AI_CONFIG, resourceId, "deadbeef");

        var captor = ArgumentCaptor.forClass(BootstrapStateEntity.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getOrganizationId()).isEqualTo(orgId);
        assertThat(saved.getResourceType()).isEqualTo(BootstrapResourceType.AI_CONFIG.name());
        assertThat(saved.getResourceId()).isEqualTo(resourceId);
        assertThat(saved.getSpecFingerprint()).isEqualTo("deadbeef");
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void recordFingerprintUpdatesExistingRow() {
        var existing = new BootstrapStateEntity();
        existing.setId(UUID.randomUUID());
        existing.setOrganizationId(orgId);
        existing.setResourceType(BootstrapResourceType.SAML_CONFIG.name());
        existing.setResourceId(resourceId);
        existing.setSpecFingerprint("old");
        when(repository.findByOrganizationIdAndResourceTypeAndResourceId(
                orgId, BootstrapResourceType.SAML_CONFIG.name(), resourceId))
                .thenReturn(Optional.of(existing));

        tracker.recordFingerprint(orgId, BootstrapResourceType.SAML_CONFIG, resourceId, "new");

        verify(repository).save(existing);
        assertThat(existing.getSpecFingerprint()).isEqualTo("new");
    }

    @Test
    void recordFingerprintAndPublishWritesRowAndFiresEvent() {
        when(repository.findByOrganizationIdAndResourceTypeAndResourceId(
                any(), any(), any())).thenReturn(Optional.empty());

        var event = new BootstrapResourceUpsertedEvent(orgId, BootstrapResourceType.DATASOURCE,
                resourceId, BootstrapChangeKind.CREATE, List.of(), Map.of("name", "x"));

        tracker.recordFingerprintAndPublish(orgId, BootstrapResourceType.DATASOURCE,
                resourceId, "fp", event);

        verify(repository).save(any(BootstrapStateEntity.class));
        verify(eventPublisher).publishEvent(event);
    }

    @Test
    void publishWithinTransactionDoesNotTouchRepository() {
        var event = new BootstrapResourceUpsertedEvent(orgId, BootstrapResourceType.ADMIN_USER,
                resourceId, BootstrapChangeKind.CREATE, List.of(), Map.of());

        tracker.publishWithinTransaction(event);

        verify(eventPublisher).publishEvent(event);
        verify(repository, never()).save(any());
    }

    @Test
    void recordFingerprintDoesNotReassignIdOnUpdate() {
        var existingId = UUID.randomUUID();
        var existing = new BootstrapStateEntity();
        existing.setId(existingId);
        existing.setOrganizationId(orgId);
        existing.setResourceType(BootstrapResourceType.OAUTH2_CONFIG.name());
        existing.setResourceId(resourceId);
        existing.setSpecFingerprint("old");
        when(repository.findByOrganizationIdAndResourceTypeAndResourceId(
                orgId, BootstrapResourceType.OAUTH2_CONFIG.name(), resourceId))
                .thenReturn(Optional.of(existing));

        tracker.recordFingerprint(orgId, BootstrapResourceType.OAUTH2_CONFIG, resourceId, "new");

        assertThat(existing.getId()).isEqualTo(existingId);
        verify(repository, never()).deleteById(any());
    }
}
