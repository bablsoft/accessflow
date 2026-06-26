package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.attestation.api.AttestationCampaignNotFoundException;
import com.bablsoft.accessflow.attestation.api.AttestationEvidenceExportService;
import com.bablsoft.accessflow.attestation.internal.config.AttestationProperties;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationCampaignEntity;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationItemEntity;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationCampaignRepository;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultAttestationEvidenceExportService implements AttestationEvidenceExportService {

    private static final String[] HEADER = {
            "item_id", "campaign_id", "campaign_name", "datasource_name", "subject_email",
            "subject_display_name", "can_read", "can_write", "can_ddl", "can_break_glass",
            "permission_expires_at", "decision", "close_reason", "decided_by", "decided_at",
            "decision_comment"
    };

    private final AttestationCampaignRepository campaignRepository;
    private final AttestationItemRepository itemRepository;
    private final AttestationProperties properties;

    @Override
    @Transactional(readOnly = true)
    public EvidenceExport export(UUID campaignId, UUID organizationId) {
        AttestationCampaignEntity campaign = campaignRepository
                .findByIdAndOrganizationId(campaignId, organizationId)
                .orElseThrow(() -> new AttestationCampaignNotFoundException(campaignId));
        List<AttestationItemEntity> items =
                itemRepository.findByCampaignIdOrderByCreatedAtAsc(campaignId);
        int cap = properties.maxEvidenceRows();
        boolean truncated = items.size() > cap;
        var rows = truncated ? items.subList(0, cap) : items;

        var sb = new StringBuilder();
        AttestationCsvWriter.appendRow(sb, HEADER);
        for (AttestationItemEntity item : rows) {
            AttestationCsvWriter.appendRow(sb,
                    item.getId().toString(),
                    item.getCampaignId().toString(),
                    campaign.getName(),
                    item.getDatasourceName(),
                    item.getSubjectUserEmail(),
                    item.getSubjectUserDisplayName(),
                    Boolean.toString(item.isCanRead()),
                    Boolean.toString(item.isCanWrite()),
                    Boolean.toString(item.isCanDdl()),
                    Boolean.toString(item.isCanBreakGlass()),
                    item.getPermissionExpiresAt() != null
                            ? item.getPermissionExpiresAt().toString() : "",
                    item.getDecision().name(),
                    item.getCloseReason() != null ? item.getCloseReason().name() : "",
                    item.getDecidedBy() != null ? item.getDecidedBy().toString() : "",
                    item.getDecidedAt() != null ? item.getDecidedAt().toString() : "",
                    item.getDecisionComment());
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        var filename = "attestation-campaign-" + campaignId + "-evidence.csv";
        return new EvidenceExport(body, filename, rows.size(), truncated);
    }
}
