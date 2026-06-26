package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.attestation.api.AttestationCampaignStatus;
import com.bablsoft.accessflow.attestation.api.AttestationItemCloseReason;
import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.api.AttestationReviewService.ItemDecisionOutcome;
import com.bablsoft.accessflow.attestation.api.AttestationReviewService.ReviewerContext;
import com.bablsoft.accessflow.attestation.api.AttestationReviewService.RowStatus;
import com.bablsoft.accessflow.attestation.api.AttestationReviewerNotEligibleException;
import com.bablsoft.accessflow.attestation.api.IllegalAttestationCampaignTransitionException;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationCampaignEntity;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationItemEntity;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationCampaignRepository;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationItemRepository;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAttestationReviewServiceTest {

    @Mock AttestationItemRepository itemRepository;
    @Mock AttestationCampaignRepository campaignRepository;
    @Mock AttestationItemStateService itemStateService;
    @Mock ReviewerEligibilityService reviewerEligibilityService;
    @Mock MessageSource messageSource;
    @InjectMocks DefaultAttestationReviewService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();
    private final UUID subjectId = UUID.randomUUID();
    private final UUID campaignId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();

    private final ReviewerContext reviewer =
            new ReviewerContext(reviewerId, orgId, UserRoleType.REVIEWER);

    private AttestationItemEntity item(UUID subject) {
        var item = new AttestationItemEntity();
        item.setId(itemId);
        item.setOrganizationId(orgId);
        item.setCampaignId(campaignId);
        item.setDatasourceId(datasourceId);
        item.setSubjectUserId(subject);
        item.setDecision(AttestationItemDecision.PENDING);
        return item;
    }

    private AttestationCampaignEntity openCampaign() {
        var c = new AttestationCampaignEntity();
        c.setId(campaignId);
        c.setOrganizationId(orgId);
        c.setStatus(AttestationCampaignStatus.OPEN);
        return c;
    }

    @Test
    void listReturnsEmptyForNonReviewerRole() {
        var page = service.listPendingForReviewer(
                new ReviewerContext(reviewerId, orgId, UserRoleType.ANALYST), PageRequest.of(0, 20));
        assertThat(page.content()).isEmpty();
        verifyNoInteractions(itemRepository);
    }

    @Test
    void listFiltersSelfSubjectAndIneligible() {
        var mine = item(reviewerId);
        var eligibleItem = item(subjectId);
        eligibleItem.setId(UUID.randomUUID());
        when(itemRepository.findItemsByCampaignStatusAndDecision(eq(orgId),
                eq(AttestationCampaignStatus.OPEN), eq(AttestationItemDecision.PENDING), any()))
                .thenReturn(new PageImpl<>(List.of(mine, eligibleItem)));
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.of(Set.of(reviewerId)));

        var page = service.listPendingForReviewer(reviewer, PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).subjectUserId()).isEqualTo(subjectId);
    }

    @Test
    void certifyDelegatesWhenEligible() {
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(item(subjectId)));
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(openCampaign()));
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.of(Set.of(reviewerId)));
        when(itemStateService.certify(itemId, reviewerId, "ok", AttestationItemCloseReason.REVIEWER))
                .thenReturn(new ItemDecisionOutcome(itemId, AttestationItemDecision.CERTIFIED, false));

        var outcome = service.certify(itemId, reviewer, "ok");

        assertThat(outcome.decision()).isEqualTo(AttestationItemDecision.CERTIFIED);
        verify(itemStateService).certify(itemId, reviewerId, "ok",
                AttestationItemCloseReason.REVIEWER);
    }

    @Test
    void certifyBlockedForSelfReview() {
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(item(reviewerId)));
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(openCampaign()));

        assertThatThrownBy(() -> service.certify(itemId, reviewer, "ok"))
                .isInstanceOf(AttestationReviewerNotEligibleException.class);
        verifyNoInteractions(itemStateService);
    }

    @Test
    void certifyRejectedWhenCampaignNotOpen() {
        var closed = openCampaign();
        closed.setStatus(AttestationCampaignStatus.CLOSED);
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(item(subjectId)));
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> service.certify(itemId, reviewer, "ok"))
                .isInstanceOf(IllegalAttestationCampaignTransitionException.class);
    }

    @Test
    void certifyRejectedWhenNotInDatasourceScope() {
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(item(subjectId)));
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(openCampaign()));
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.of(Set.of(UUID.randomUUID())));

        assertThatThrownBy(() -> service.certify(itemId, reviewer, "ok"))
                .isInstanceOf(AttestationReviewerNotEligibleException.class);
    }

    @Test
    void adminEligibleWhenNoDatasourceScopedReviewers() {
        var admin = new ReviewerContext(reviewerId, orgId, UserRoleType.ADMIN);
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(item(subjectId)));
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(openCampaign()));
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.empty());
        when(itemStateService.revoke(itemId, reviewerId, "gone", AttestationItemCloseReason.REVIEWER))
                .thenReturn(new ItemDecisionOutcome(itemId, AttestationItemDecision.REVOKED, false));

        var outcome = service.revoke(itemId, admin, "gone");

        assertThat(outcome.decision()).isEqualTo(AttestationItemDecision.REVOKED);
    }

    @Test
    void bulkDecideEvaluatesEachRowIndependently() {
        lenient().when(messageSource.getMessage(anyString(), any(), any())).thenReturn("err");
        var goodId = itemId;
        var missingId = UUID.randomUUID();
        // good row
        when(itemRepository.findById(goodId)).thenReturn(Optional.of(item(subjectId)));
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(openCampaign()));
        when(reviewerEligibilityService.findEligibleReviewerIds(datasourceId))
                .thenReturn(Optional.of(Set.of(reviewerId)));
        when(itemStateService.certify(goodId, reviewerId, "c", AttestationItemCloseReason.REVIEWER))
                .thenReturn(new ItemDecisionOutcome(goodId, AttestationItemDecision.CERTIFIED, false));
        // missing row
        when(itemRepository.findById(missingId)).thenReturn(Optional.empty());

        var outcome = service.bulkDecide(List.of(goodId, missingId),
                AttestationItemDecision.CERTIFIED, reviewer, "c");

        assertThat(outcome.rows()).hasSize(2);
        assertThat(outcome.rows().get(0).status()).isEqualTo(RowStatus.SUCCESS);
        assertThat(outcome.rows().get(1).status()).isEqualTo(RowStatus.NOT_FOUND);
    }
}
