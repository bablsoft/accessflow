package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.attestation.api.AttestationCampaignLookupService;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignSummary;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationCampaignRepository;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationItemRepository;
import com.bablsoft.accessflow.core.api.ReviewerEligibilityService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultAttestationCampaignLookupService implements AttestationCampaignLookupService {

    private final AttestationCampaignRepository campaignRepository;
    private final AttestationItemRepository itemRepository;
    private final ReviewerEligibilityService reviewerEligibilityService;
    private final UserQueryService userQueryService;

    @Override
    @Transactional(readOnly = true)
    public Optional<AttestationCampaignSummary> findSummary(UUID campaignId) {
        return campaignRepository.findById(campaignId).map(AttestationViewMapper::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findRecipientUserIds(UUID campaignId) {
        var campaign = campaignRepository.findById(campaignId).orElse(null);
        if (campaign == null) {
            return Set.of();
        }
        Set<UUID> recipients = new LinkedHashSet<>();
        for (UUID datasourceId : itemRepository.findDistinctDatasourceIdsByCampaignId(campaignId)) {
            reviewerEligibilityService.findEligibleReviewerIds(datasourceId)
                    .ifPresent(recipients::addAll);
        }
        // Active org admins always receive the campaign-opened notification — and cover any datasource
        // that has no scoped reviewers.
        userQueryService.findByOrganizationAndRole(campaign.getOrganizationId(), UserRoleType.ADMIN)
                .stream()
                .filter(UserView::active)
                .forEach(u -> recipients.add(u.id()));
        return recipients;
    }
}
