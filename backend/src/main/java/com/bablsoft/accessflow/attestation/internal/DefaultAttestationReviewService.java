package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignNotFoundException;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignStatus;
import com.bablsoft.accessflow.attestation.api.AttestationItemCloseReason;
import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.api.AttestationItemNotFoundException;
import com.bablsoft.accessflow.attestation.api.AttestationReviewService;
import com.bablsoft.accessflow.attestation.api.AttestationReviewerNotEligibleException;
import com.bablsoft.accessflow.attestation.api.AttestationItemView;
import com.bablsoft.accessflow.attestation.api.IllegalAttestationCampaignTransitionException;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationItemEntity;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationCampaignRepository;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationItemRepository;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultAttestationReviewService implements AttestationReviewService {


    private final AttestationItemRepository itemRepository;
    private final AttestationCampaignRepository campaignRepository;
    private final AttestationItemStateService itemStateService;
    private final ReviewerEligibilityService reviewerEligibilityService;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AttestationItemView> listPendingForReviewer(ReviewerContext context,
                                                                    PageRequest pageRequest) {
        if (!has(context, Permission.ATTESTATION_REVIEW)) {
            return PageResponse.empty(pageRequest.page(), pageRequest.size());
        }
        var pageable = AttestationPageAdapter.toSpringPageable(pageRequest);
        var page = itemRepository.findItemsByCampaignStatusAndDecision(context.organizationId(),
                AttestationCampaignStatus.OPEN, AttestationItemDecision.PENDING, pageable);
        var visible = page.getContent().stream()
                .filter(item -> isEligible(context, item))
                .map(AttestationViewMapper::toItemView)
                .toList();
        return new PageResponse<>(visible, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    @Override
    @Transactional
    public ItemDecisionOutcome certify(UUID itemId, ReviewerContext context, String comment) {
        prepare(itemId, context);
        return itemStateService.certify(itemId, context.userId(), comment,
                AttestationItemCloseReason.REVIEWER);
    }

    @Override
    @Transactional
    public ItemDecisionOutcome revoke(UUID itemId, ReviewerContext context, String comment) {
        prepare(itemId, context);
        return itemStateService.revoke(itemId, context.userId(), comment,
                AttestationItemCloseReason.REVIEWER);
    }

    @Override
    public BulkItemDecisionOutcome bulkDecide(List<UUID> itemIds, AttestationItemDecision decision,
                                              ReviewerContext context, String comment) {
        // Intentionally NOT @Transactional. Each row delegates to the single-row entry point; the
        // mutating bean (AttestationItemStateService) starts its own transaction, so a per-row
        // failure cannot poison a successful peer.
        var rows = new ArrayList<RowOutcome>(itemIds.size());
        for (UUID itemId : itemIds) {
            rows.add(decideOne(itemId, decision, context, comment));
        }
        return new BulkItemDecisionOutcome(List.copyOf(rows));
    }

    private RowOutcome decideOne(UUID itemId, AttestationItemDecision decision,
                                 ReviewerContext context, String comment) {
        try {
            var outcome = switch (decision) {
                case CERTIFIED -> certify(itemId, context, comment);
                case REVOKED -> revoke(itemId, context, comment);
                case PENDING -> throw new IllegalArgumentException(
                        "Bulk decision must be CERTIFIED or REVOKED");
            };
            return RowOutcome.success(itemId, outcome);
        } catch (AttestationItemNotFoundException ex) {
            return RowOutcome.failure(itemId, RowStatus.NOT_FOUND,
                    "ATTESTATION_ITEM_NOT_FOUND", msg("error.attestation_item_not_found"));
        } catch (AttestationReviewerNotEligibleException ex) {
            return RowOutcome.failure(itemId, RowStatus.FORBIDDEN,
                    "ATTESTATION_REVIEWER_NOT_ELIGIBLE",
                    msg("error.attestation_reviewer_not_eligible"));
        } catch (IllegalAttestationCampaignTransitionException ex) {
            return RowOutcome.failure(itemId, RowStatus.INVALID_STATE,
                    "ATTESTATION_CAMPAIGN_INVALID_STATE",
                    msg("error.attestation_campaign_invalid_state"));
        } catch (RuntimeException ex) {
            log.error("Unexpected error during bulk attestation decision for item {}", itemId, ex);
            throw ex;
        }
    }

    private void prepare(UUID itemId, ReviewerContext context) {
        var item = itemRepository.findById(itemId)
                .orElseThrow(() -> new AttestationItemNotFoundException(itemId));
        if (!item.getOrganizationId().equals(context.organizationId())) {
            throw new AttestationItemNotFoundException(itemId);
        }
        var campaign = campaignRepository.findById(item.getCampaignId())
                .orElseThrow(() -> new AttestationCampaignNotFoundException(item.getCampaignId()));
        if (campaign.getStatus() != AttestationCampaignStatus.OPEN) {
            throw new IllegalAttestationCampaignTransitionException(campaign.getStatus(),
                    "Campaign is not OPEN");
        }
        if (!has(context, Permission.ATTESTATION_REVIEW)) {
            throw new AttestationReviewerNotEligibleException(context.userId(), itemId);
        }
        if (item.getSubjectUserId().equals(context.userId())) {
            throw new AttestationReviewerNotEligibleException(context.userId(), itemId);
        }
        if (!isEligibleForDatasource(context, item.getDatasourceId())) {
            throw new AttestationReviewerNotEligibleException(context.userId(), itemId);
        }
    }

    private boolean isEligible(ReviewerContext context, AttestationItemEntity item) {
        if (item.getSubjectUserId().equals(context.userId())) {
            return false;
        }
        return isEligibleForDatasource(context, item.getDatasourceId());
    }

    private boolean isEligibleForDatasource(ReviewerContext context, UUID datasourceId) {
        var eligible = reviewerEligibilityService.findEligibleReviewerIds(datasourceId);
        if (eligible.isPresent()) {
            return eligible.get().contains(context.userId());
        }
        // No datasource-scoped reviewers configured — only org admins may attest this datasource.
        return has(context, Permission.REVIEW_OVERRIDE);
    }

    private static boolean has(ReviewerContext context, Permission permission) {
        return context.permissions() != null && context.permissions().contains(permission);
    }

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }
}
