package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.attestation.api.AttestationCampaignScope;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignStatus;
import com.bablsoft.accessflow.attestation.api.AttestationItemCloseReason;
import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.api.AttestationLifecycleService;
import com.bablsoft.accessflow.attestation.api.AttestationPendingDefault;
import com.bablsoft.accessflow.attestation.events.AttestationCampaignOpenedEvent;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationCampaignEntity;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationItemEntity;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationCampaignRepository;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationItemRepository;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourcePermissionView;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultAttestationLifecycleService implements AttestationLifecycleService {

    private final AttestationCampaignRepository campaignRepository;
    private final AttestationItemRepository itemRepository;
    private final AttestationItemStateService itemStateService;
    private final DatasourceAdminService datasourceAdminService;
    private final DatasourceLookupService datasourceLookupService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findCampaignIdsDueToOpen(Instant now) {
        return campaignRepository.findIdsByStatusAndScheduledOpenAtBefore(
                AttestationCampaignStatus.SCHEDULED, now);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findCampaignIdsDueToClose(Instant now) {
        return campaignRepository.findIdsByStatusAndDueAtBefore(
                AttestationCampaignStatus.OPEN, now);
    }

    @Override
    @Transactional
    public boolean openCampaign(UUID campaignId) {
        var campaign = campaignRepository.findByIdForUpdate(campaignId).orElse(null);
        if (campaign == null || campaign.getStatus() != AttestationCampaignStatus.SCHEDULED) {
            return false;
        }
        int inserted = snapshotGrants(campaign);
        campaign.setStatus(AttestationCampaignStatus.OPEN);
        campaign.setOpenedAt(Instant.now());
        campaign.setTotalItems(inserted);
        campaignRepository.save(campaign);
        eventPublisher.publishEvent(
                new AttestationCampaignOpenedEvent(campaign.getId(), campaign.getOrganizationId()));
        recordAudit(AuditAction.ATTESTATION_CAMPAIGN_OPENED,
                AuditResourceType.ATTESTATION_CAMPAIGN, campaign.getId(),
                campaign.getOrganizationId(), openMetadata(campaign, inserted));
        log.info("Opened attestation campaign {} with {} items", campaign.getId(), inserted);
        return true;
    }

    @Override
    @Transactional
    public boolean closeCampaign(UUID campaignId) {
        var campaign = campaignRepository.findByIdForUpdate(campaignId).orElse(null);
        if (campaign == null || campaign.getStatus() != AttestationCampaignStatus.OPEN) {
            return false;
        }
        var pending = itemRepository.findByCampaignIdAndDecision(campaignId,
                AttestationItemDecision.PENDING);
        int autoCertified = 0;
        int autoRevoked = 0;
        boolean revokeDefault = campaign.getPendingDefault() == AttestationPendingDefault.REVOKE;
        for (AttestationItemEntity item : pending) {
            if (revokeDefault) {
                itemStateService.revoke(item.getId(), null, null,
                        AttestationItemCloseReason.AUTO_DEFAULT_REVOKE);
                recordItemAudit(AuditAction.ATTESTATION_ITEM_REVOKED, item, "auto_default");
                autoRevoked++;
            } else {
                itemStateService.certify(item.getId(), null, null,
                        AttestationItemCloseReason.AUTO_DEFAULT_KEEP);
                recordItemAudit(AuditAction.ATTESTATION_ITEM_CERTIFIED, item, "auto_default");
                autoCertified++;
            }
        }
        campaign.setStatus(AttestationCampaignStatus.CLOSED);
        campaign.setClosedAt(Instant.now());
        campaignRepository.save(campaign);
        recordAudit(AuditAction.ATTESTATION_CAMPAIGN_CLOSED,
                AuditResourceType.ATTESTATION_CAMPAIGN, campaign.getId(),
                campaign.getOrganizationId(), closeMetadata(autoCertified, autoRevoked));
        log.info("Closed attestation campaign {} (auto-certified {}, auto-revoked {})",
                campaign.getId(), autoCertified, autoRevoked);
        return true;
    }

    private int snapshotGrants(AttestationCampaignEntity campaign) {
        int inserted = 0;
        for (DatasourceSnapshotSource source : sources(campaign)) {
            var permissions = datasourceAdminService.listPermissions(
                    source.datasourceId(), campaign.getOrganizationId());
            for (DatasourcePermissionView view : permissions) {
                if (itemRepository.existsByCampaignIdAndPermissionId(campaign.getId(), view.id())) {
                    continue;
                }
                itemRepository.save(toItem(campaign, source.datasourceName(), view));
                inserted++;
            }
        }
        return inserted;
    }

    private List<DatasourceSnapshotSource> sources(AttestationCampaignEntity campaign) {
        if (campaign.getScope() == AttestationCampaignScope.DATASOURCE) {
            var name = datasourceLookupService.findRef(campaign.getDatasourceId())
                    .map(DatasourceRef::name)
                    .orElse(campaign.getDatasourceId().toString());
            return List.of(new DatasourceSnapshotSource(campaign.getDatasourceId(), name));
        }
        return datasourceLookupService.findActiveRefsByOrganization(campaign.getOrganizationId())
                .stream()
                .map(ref -> new DatasourceSnapshotSource(ref.id(), ref.name()))
                .toList();
    }

    private AttestationItemEntity toItem(AttestationCampaignEntity campaign, String datasourceName,
                                         DatasourcePermissionView view) {
        var item = new AttestationItemEntity();
        item.setId(UUID.randomUUID());
        item.setCampaignId(campaign.getId());
        item.setOrganizationId(campaign.getOrganizationId());
        item.setPermissionId(view.id());
        item.setDatasourceId(view.datasourceId());
        item.setDatasourceName(datasourceName);
        item.setSubjectUserId(view.userId());
        item.setSubjectUserEmail(view.userEmail());
        item.setSubjectUserDisplayName(view.userDisplayName());
        item.setCanRead(view.canRead());
        item.setCanWrite(view.canWrite());
        item.setCanDdl(view.canDdl());
        item.setCanBreakGlass(view.canBreakGlass());
        item.setPermissionExpiresAt(view.expiresAt());
        item.setPermissionCreatedAt(view.createdAt());
        item.setPermissionSnapshot(toSnapshotJson(view));
        item.setDecision(AttestationItemDecision.PENDING);
        return item;
    }

    private String toSnapshotJson(DatasourcePermissionView view) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("permission_id", view.id().toString());
        node.put("datasource_id", view.datasourceId().toString());
        node.put("user_id", view.userId().toString());
        node.put("user_email", view.userEmail());
        node.put("user_display_name", view.userDisplayName());
        node.put("can_read", view.canRead());
        node.put("can_write", view.canWrite());
        node.put("can_ddl", view.canDdl());
        node.put("can_break_glass", view.canBreakGlass());
        if (view.rowLimitOverride() != null) {
            node.put("row_limit_override", view.rowLimitOverride());
        } else {
            node.putNull("row_limit_override");
        }
        putStringArray(node, "allowed_schemas", view.allowedSchemas());
        putStringArray(node, "allowed_tables", view.allowedTables());
        putStringArray(node, "restricted_columns", view.restrictedColumns());
        node.put("expires_at", view.expiresAt() != null ? view.expiresAt().toString() : null);
        node.put("created_by", view.createdBy() != null ? view.createdBy().toString() : null);
        node.put("created_at", view.createdAt() != null ? view.createdAt().toString() : null);
        return node.toString();
    }

    private static void putStringArray(ObjectNode node, String field, List<String> values) {
        ArrayNode arr = node.putArray(field);
        if (values != null) {
            values.forEach(arr::add);
        }
    }

    private void recordItemAudit(AuditAction action, AttestationItemEntity item, String reason) {
        var metadata = new HashMap<String, Object>();
        metadata.put("campaign_id", item.getCampaignId().toString());
        metadata.put("permission_id", item.getPermissionId().toString());
        metadata.put("subject_user_id", item.getSubjectUserId().toString());
        metadata.put("reason", reason);
        recordAudit(action, AuditResourceType.ATTESTATION_ITEM, item.getId(),
                item.getOrganizationId(), metadata);
    }

    private void recordAudit(AuditAction action, AuditResourceType resourceType, UUID resourceId,
                             UUID organizationId, HashMap<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(action, resourceType, resourceId, organizationId,
                    null, metadata, null, null));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on {}", action, resourceId, ex);
        }
    }

    private static HashMap<String, Object> openMetadata(AttestationCampaignEntity campaign,
                                                        int itemCount) {
        var metadata = new HashMap<String, Object>();
        metadata.put("scope", campaign.getScope().name());
        metadata.put("total_items", itemCount);
        if (campaign.getDatasourceId() != null) {
            metadata.put("datasource_id", campaign.getDatasourceId().toString());
        }
        return metadata;
    }

    private static HashMap<String, Object> closeMetadata(int autoCertified, int autoRevoked) {
        var metadata = new HashMap<String, Object>();
        metadata.put("auto_certified", autoCertified);
        metadata.put("auto_revoked", autoRevoked);
        return metadata;
    }

    private record DatasourceSnapshotSource(UUID datasourceId, String datasourceName) {
    }
}
