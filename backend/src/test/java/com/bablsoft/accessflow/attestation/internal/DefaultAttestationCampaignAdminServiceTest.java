package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.attestation.api.AttestationCampaignNotFoundException;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignScope;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignStatus;
import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.api.AttestationLifecycleService;
import com.bablsoft.accessflow.attestation.api.AttestationPendingDefault;
import com.bablsoft.accessflow.attestation.api.CreateAttestationCampaignCommand;
import com.bablsoft.accessflow.attestation.api.IllegalAttestationCampaignTransitionException;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationCampaignEntity;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationCampaignRepository;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationItemRepository;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.PageRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAttestationCampaignAdminServiceTest {

    @Mock AttestationCampaignRepository campaignRepository;
    @Mock AttestationItemRepository itemRepository;
    @Mock AttestationLifecycleService lifecycleService;
    @Mock DatasourceAdminService datasourceAdminService;
    @InjectMocks DefaultAttestationCampaignAdminService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID creator = UUID.randomUUID();
    private final UUID campaignId = UUID.randomUUID();
    private final Instant open = Instant.parse("2026-07-01T00:00:00Z");
    private final Instant due = Instant.parse("2026-07-08T00:00:00Z");

    private AttestationCampaignEntity scheduledOrgCampaign() {
        var c = new AttestationCampaignEntity();
        c.setId(campaignId);
        c.setOrganizationId(orgId);
        c.setName("Org review");
        c.setScope(AttestationCampaignScope.ORGANIZATION);
        c.setStatus(AttestationCampaignStatus.SCHEDULED);
        c.setPendingDefault(AttestationPendingDefault.KEEP);
        c.setScheduledOpenAt(open);
        c.setDueAt(due);
        c.setCreatedBy(creator);
        return c;
    }

    @Test
    void createsOrganizationCampaign() {
        var cmd = new CreateAttestationCampaignCommand(orgId, creator, "Org review", null,
                AttestationCampaignScope.ORGANIZATION, null, AttestationPendingDefault.KEEP, open, due);
        var view = service.create(cmd);
        assertThat(view.name()).isEqualTo("Org review");
        assertThat(view.status()).isEqualTo(AttestationCampaignStatus.SCHEDULED);
        verify(campaignRepository).save(any(AttestationCampaignEntity.class));
        verify(datasourceAdminService, org.mockito.Mockito.never()).getForAdmin(any(), any());
    }

    @Test
    void listWithoutFilterUsesOrgQuery() {
        when(campaignRepository.findByOrganizationId(eq(orgId), any()))
                .thenReturn(new PageImpl<>(List.of(scheduledOrgCampaign())));
        var page = service.list(orgId, null, PageRequest.of(0, 20));
        assertThat(page.content()).hasSize(1);
    }

    @Test
    void listWithFilterUsesStatusQuery() {
        when(campaignRepository.findByOrganizationIdAndStatus(eq(orgId),
                eq(AttestationCampaignStatus.OPEN), any()))
                .thenReturn(new PageImpl<>(List.of(),
                        org.springframework.data.domain.PageRequest.of(0, 20), 0));
        service.list(orgId, AttestationCampaignStatus.OPEN, PageRequest.of(0, 20));
        verify(campaignRepository).findByOrganizationIdAndStatus(eq(orgId),
                eq(AttestationCampaignStatus.OPEN), any());
    }

    @Test
    void getReturnsViewWithCounts() {
        when(campaignRepository.findByIdAndOrganizationId(campaignId, orgId))
                .thenReturn(Optional.of(scheduledOrgCampaign()));
        when(itemRepository.countByCampaignIdAndDecision(campaignId, AttestationItemDecision.PENDING))
                .thenReturn(2L);
        when(itemRepository.countByCampaignIdAndDecision(campaignId,
                AttestationItemDecision.CERTIFIED)).thenReturn(3L);
        when(itemRepository.countByCampaignIdAndDecision(campaignId, AttestationItemDecision.REVOKED))
                .thenReturn(1L);
        var view = service.get(campaignId, orgId);
        assertThat(view.pendingItems()).isEqualTo(2);
        assertThat(view.certifiedItems()).isEqualTo(3);
        assertThat(view.revokedItems()).isEqualTo(1);
    }

    @Test
    void getThrowsWhenMissing() {
        when(campaignRepository.findByIdAndOrganizationId(campaignId, orgId))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(campaignId, orgId))
                .isInstanceOf(AttestationCampaignNotFoundException.class);
    }

    @Test
    void cancelTransitionsScheduledToCancelled() {
        var c = scheduledOrgCampaign();
        when(campaignRepository.findByIdForUpdate(campaignId)).thenReturn(Optional.of(c));
        service.cancel(campaignId, orgId);
        assertThat(c.getStatus()).isEqualTo(AttestationCampaignStatus.CANCELLED);
    }

    @Test
    void cancelRejectsNonScheduled() {
        var c = scheduledOrgCampaign();
        c.setStatus(AttestationCampaignStatus.OPEN);
        when(campaignRepository.findByIdForUpdate(campaignId)).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.cancel(campaignId, orgId))
                .isInstanceOf(IllegalAttestationCampaignTransitionException.class);
    }

    @Test
    void cancelRejectsCrossOrg() {
        var c = scheduledOrgCampaign();
        when(campaignRepository.findByIdForUpdate(campaignId)).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.cancel(campaignId, UUID.randomUUID()))
                .isInstanceOf(AttestationCampaignNotFoundException.class);
    }

    @Test
    void openNowDelegatesToLifecycleThenReads() {
        when(campaignRepository.findByIdAndOrganizationId(campaignId, orgId))
                .thenReturn(Optional.of(scheduledOrgCampaign()));
        service.openNow(campaignId, orgId);
        verify(lifecycleService).openCampaign(campaignId);
    }
}
