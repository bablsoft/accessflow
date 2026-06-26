package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.attestation.api.AttestationCampaignScope;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignStatus;
import com.bablsoft.accessflow.attestation.api.AttestationItemCloseReason;
import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.api.AttestationPendingDefault;
import com.bablsoft.accessflow.attestation.events.AttestationCampaignOpenedEvent;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationCampaignEntity;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationItemEntity;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationCampaignRepository;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationItemRepository;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourcePermissionView;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAttestationLifecycleServiceTest {

    @Mock AttestationCampaignRepository campaignRepository;
    @Mock AttestationItemRepository itemRepository;
    @Mock AttestationItemStateService itemStateService;
    @Mock DatasourceAdminService datasourceAdminService;
    @Mock DatasourceLookupService datasourceLookupService;
    @Mock com.bablsoft.accessflow.audit.api.AuditLogService auditLogService;
    @Mock ApplicationEventPublisher eventPublisher;

    DefaultAttestationLifecycleService service;

    private final UUID campaignId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultAttestationLifecycleService(campaignRepository, itemRepository,
                itemStateService, datasourceAdminService, datasourceLookupService, auditLogService,
                eventPublisher, new ObjectMapper());
    }

    private AttestationCampaignEntity scheduledDatasourceCampaign() {
        var c = new AttestationCampaignEntity();
        c.setId(campaignId);
        c.setOrganizationId(orgId);
        c.setScope(AttestationCampaignScope.DATASOURCE);
        c.setDatasourceId(datasourceId);
        c.setStatus(AttestationCampaignStatus.SCHEDULED);
        c.setPendingDefault(AttestationPendingDefault.KEEP);
        return c;
    }

    private DatasourcePermissionView permission(UUID userId) {
        return new DatasourcePermissionView(UUID.randomUUID(), datasourceId, userId,
                userId + "@example.com", "User", true, false, false, false, null,
                List.of("public"), List.of(), List.of(), null, UUID.randomUUID(), Instant.now());
    }

    @Test
    void openSnapshotsGrantsAndPublishesEvent() {
        when(campaignRepository.findByIdForUpdate(campaignId))
                .thenReturn(Optional.of(scheduledDatasourceCampaign()));
        when(datasourceLookupService.findRef(datasourceId))
                .thenReturn(Optional.of(new DatasourceRef(datasourceId, "Production")));
        when(datasourceAdminService.listPermissions(datasourceId, orgId))
                .thenReturn(List.of(permission(UUID.randomUUID()), permission(UUID.randomUUID())));
        when(itemRepository.existsByCampaignIdAndPermissionId(any(), any())).thenReturn(false);

        boolean opened = service.openCampaign(campaignId);

        assertThat(opened).isTrue();
        verify(itemRepository, times(2)).save(any(AttestationItemEntity.class));
        verify(eventPublisher).publishEvent(any(AttestationCampaignOpenedEvent.class));
        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().action())
                .isEqualTo(AuditAction.ATTESTATION_CAMPAIGN_OPENED);
    }

    @Test
    void openIsIdempotentForNonScheduledCampaign() {
        var c = scheduledDatasourceCampaign();
        c.setStatus(AttestationCampaignStatus.OPEN);
        when(campaignRepository.findByIdForUpdate(campaignId)).thenReturn(Optional.of(c));

        assertThat(service.openCampaign(campaignId)).isFalse();
        verify(itemRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void openSkipsAlreadySnapshottedPermission() {
        when(campaignRepository.findByIdForUpdate(campaignId))
                .thenReturn(Optional.of(scheduledDatasourceCampaign()));
        when(datasourceLookupService.findRef(datasourceId))
                .thenReturn(Optional.of(new DatasourceRef(datasourceId, "Production")));
        when(datasourceAdminService.listPermissions(datasourceId, orgId))
                .thenReturn(List.of(permission(UUID.randomUUID())));
        when(itemRepository.existsByCampaignIdAndPermissionId(any(), any())).thenReturn(true);

        boolean opened = service.openCampaign(campaignId);

        assertThat(opened).isTrue();
        verify(itemRepository, never()).save(any());
    }

    @Test
    void closeRevokesPendingItemsUnderRevokeDefault() {
        var c = scheduledDatasourceCampaign();
        c.setStatus(AttestationCampaignStatus.OPEN);
        c.setPendingDefault(AttestationPendingDefault.REVOKE);
        when(campaignRepository.findByIdForUpdate(campaignId)).thenReturn(Optional.of(c));
        var item = new AttestationItemEntity();
        item.setId(UUID.randomUUID());
        item.setCampaignId(campaignId);
        item.setOrganizationId(orgId);
        item.setPermissionId(UUID.randomUUID());
        item.setSubjectUserId(UUID.randomUUID());
        when(itemRepository.findByCampaignIdAndDecision(campaignId, AttestationItemDecision.PENDING))
                .thenReturn(List.of(item));

        boolean closed = service.closeCampaign(campaignId);

        assertThat(closed).isTrue();
        assertThat(c.getStatus()).isEqualTo(AttestationCampaignStatus.CLOSED);
        verify(itemStateService).revoke(item.getId(), null, null,
                AttestationItemCloseReason.AUTO_DEFAULT_REVOKE);
    }

    @Test
    void closeCertifiesPendingItemsUnderKeepDefault() {
        var c = scheduledDatasourceCampaign();
        c.setStatus(AttestationCampaignStatus.OPEN);
        c.setPendingDefault(AttestationPendingDefault.KEEP);
        when(campaignRepository.findByIdForUpdate(campaignId)).thenReturn(Optional.of(c));
        var item = new AttestationItemEntity();
        item.setId(UUID.randomUUID());
        item.setCampaignId(campaignId);
        item.setOrganizationId(orgId);
        item.setPermissionId(UUID.randomUUID());
        item.setSubjectUserId(UUID.randomUUID());
        when(itemRepository.findByCampaignIdAndDecision(campaignId, AttestationItemDecision.PENDING))
                .thenReturn(List.of(item));

        service.closeCampaign(campaignId);

        verify(itemStateService).certify(item.getId(), null, null,
                AttestationItemCloseReason.AUTO_DEFAULT_KEEP);
    }

    @Test
    void closeIsIdempotentForNonOpenCampaign() {
        var c = scheduledDatasourceCampaign();
        when(campaignRepository.findByIdForUpdate(campaignId)).thenReturn(Optional.of(c));
        assertThat(service.closeCampaign(campaignId)).isFalse();
    }

    @Test
    void findDueDelegatesToRepository() {
        var now = Instant.now();
        when(campaignRepository.findIdsByStatusAndScheduledOpenAtBefore(
                AttestationCampaignStatus.SCHEDULED, now)).thenReturn(List.of(campaignId));
        when(campaignRepository.findIdsByStatusAndDueAtBefore(
                AttestationCampaignStatus.OPEN, now)).thenReturn(List.of(campaignId));
        assertThat(service.findCampaignIdsDueToOpen(now)).containsExactly(campaignId);
        assertThat(service.findCampaignIdsDueToClose(now)).containsExactly(campaignId);
    }
}
