package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationCampaignEntity;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationCampaignRepository;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationItemRepository;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAttestationCampaignLookupServiceTest {

    @Mock AttestationCampaignRepository campaignRepository;
    @Mock AttestationItemRepository itemRepository;
    @Mock ReviewerEligibilityService reviewerEligibilityService;
    @Mock UserQueryService userQueryService;
    @InjectMocks DefaultAttestationCampaignLookupService service;

    private final UUID campaignId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    private AttestationCampaignEntity campaign() {
        var c = new AttestationCampaignEntity();
        c.setId(campaignId);
        c.setOrganizationId(orgId);
        c.setName("Q3");
        c.setDueAt(Instant.parse("2026-07-08T00:00:00Z"));
        return c;
    }

    private UserView admin(UUID id) {
        var u = mock(UserView.class);
        when(u.id()).thenReturn(id);
        when(u.active()).thenReturn(true);
        return u;
    }

    @Test
    void findSummaryMapsEntity() {
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign()));
        var summary = service.findSummary(campaignId).orElseThrow();
        assertThat(summary.name()).isEqualTo("Q3");
        assertThat(summary.organizationId()).isEqualTo(orgId);
    }

    @Test
    void findSummaryEmptyWhenMissing() {
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.empty());
        assertThat(service.findSummary(campaignId)).isEmpty();
    }

    @Test
    void recipientsUnionEligibleReviewersAndActiveAdmins() {
        var reviewer = UUID.randomUUID();
        var adminId = UUID.randomUUID();
        var adminUser = admin(adminId);
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign()));
        when(itemRepository.findDistinctDatasourceIdsByCampaignId(campaignId))
                .thenReturn(List.of(datasourceId));
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.of(Set.of(reviewer)));
        when(userQueryService.findByOrganizationAndRole(orgId, UserRoleType.ADMIN))
                .thenReturn(List.of(adminUser));

        var recipients = service.findRecipientUserIds(campaignId);

        assertThat(recipients).containsExactlyInAnyOrder(reviewer, adminId);
    }

    @Test
    void recipientsEmptyWhenCampaignMissing() {
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.empty());
        assertThat(service.findRecipientUserIds(campaignId)).isEmpty();
    }
}
