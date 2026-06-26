package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.attestation.api.AttestationCampaignAdminService;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignNotFoundException;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignStatus;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignView;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignScope;
import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.api.AttestationItemView;
import com.bablsoft.accessflow.attestation.api.AttestationLifecycleService;
import com.bablsoft.accessflow.attestation.api.CreateAttestationCampaignCommand;
import com.bablsoft.accessflow.attestation.api.IllegalAttestationCampaignTransitionException;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationCampaignEntity;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationCampaignRepository;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationItemRepository;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultAttestationCampaignAdminService implements AttestationCampaignAdminService {

    private final AttestationCampaignRepository campaignRepository;
    private final AttestationItemRepository itemRepository;
    private final AttestationLifecycleService lifecycleService;
    private final DatasourceAdminService datasourceAdminService;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AttestationCampaignView> list(UUID organizationId,
                                                      AttestationCampaignStatus statusFilter,
                                                      PageRequest pageRequest) {
        var pageable = AttestationPageAdapter.toSpringPageable(pageRequest);
        var page = statusFilter == null
                ? campaignRepository.findByOrganizationId(organizationId, pageable)
                : campaignRepository.findByOrganizationIdAndStatus(organizationId, statusFilter,
                        pageable);
        return AttestationPageAdapter.toPageResponse(page)
                .map(e -> AttestationViewMapper.toCampaignView(e, null, 0, 0, 0));
    }

    @Override
    @Transactional(readOnly = true)
    public AttestationCampaignView get(UUID campaignId, UUID organizationId) {
        var entity = loadInOrg(campaignId, organizationId);
        return toViewWithCounts(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AttestationItemView> listItems(UUID campaignId, UUID organizationId,
                                                       PageRequest pageRequest) {
        loadInOrg(campaignId, organizationId);
        var pageable = AttestationPageAdapter.toSpringPageable(pageRequest);
        return AttestationPageAdapter.toPageResponse(
                        itemRepository.findByCampaignId(campaignId, pageable))
                .map(AttestationViewMapper::toItemView);
    }

    @Override
    @Transactional
    public AttestationCampaignView create(CreateAttestationCampaignCommand command) {
        String datasourceName = null;
        if (command.scope() == AttestationCampaignScope.DATASOURCE) {
            // Validates the datasource belongs to the caller's organization (throws otherwise).
            var ds = datasourceAdminService.getForAdmin(command.datasourceId(),
                    command.organizationId());
            datasourceName = ds.name();
        }
        var entity = new AttestationCampaignEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(command.organizationId());
        entity.setName(command.name());
        entity.setDescription(command.description());
        entity.setScope(command.scope());
        entity.setDatasourceId(command.datasourceId());
        entity.setStatus(AttestationCampaignStatus.SCHEDULED);
        entity.setPendingDefault(command.pendingDefault());
        entity.setScheduledOpenAt(command.scheduledOpenAt());
        entity.setDueAt(command.dueAt());
        entity.setCreatedBy(command.createdBy());
        campaignRepository.save(entity);
        return AttestationViewMapper.toCampaignView(entity, datasourceName, 0, 0, 0);
    }

    @Override
    public AttestationCampaignView openNow(UUID campaignId, UUID organizationId) {
        loadInOrg(campaignId, organizationId);
        // openCampaign is its own transaction (separate bean); it commits and fires the opened event
        // before we re-read. Idempotent — a no-op when the campaign is already OPEN.
        lifecycleService.openCampaign(campaignId);
        return get(campaignId, organizationId);
    }

    @Override
    @Transactional
    public void cancel(UUID campaignId, UUID organizationId) {
        var entity = campaignRepository.findByIdForUpdate(campaignId)
                .orElseThrow(() -> new AttestationCampaignNotFoundException(campaignId));
        if (!entity.getOrganizationId().equals(organizationId)) {
            throw new AttestationCampaignNotFoundException(campaignId);
        }
        if (entity.getStatus() != AttestationCampaignStatus.SCHEDULED) {
            throw new IllegalAttestationCampaignTransitionException(entity.getStatus(),
                    "Only a SCHEDULED campaign can be cancelled");
        }
        entity.setStatus(AttestationCampaignStatus.CANCELLED);
        campaignRepository.save(entity);
    }

    private AttestationCampaignEntity loadInOrg(UUID campaignId, UUID organizationId) {
        return campaignRepository.findByIdAndOrganizationId(campaignId, organizationId)
                .orElseThrow(() -> new AttestationCampaignNotFoundException(campaignId));
    }

    private AttestationCampaignView toViewWithCounts(AttestationCampaignEntity entity) {
        int pending = (int) itemRepository.countByCampaignIdAndDecision(entity.getId(),
                AttestationItemDecision.PENDING);
        int certified = (int) itemRepository.countByCampaignIdAndDecision(entity.getId(),
                AttestationItemDecision.CERTIFIED);
        int revoked = (int) itemRepository.countByCampaignIdAndDecision(entity.getId(),
                AttestationItemDecision.REVOKED);
        String datasourceName = entity.getDatasourceId() != null
                ? datasourceAdminService.getForAdmin(entity.getDatasourceId(),
                        entity.getOrganizationId()).name()
                : null;
        return AttestationViewMapper.toCampaignView(entity, datasourceName, pending, certified,
                revoked);
    }
}
