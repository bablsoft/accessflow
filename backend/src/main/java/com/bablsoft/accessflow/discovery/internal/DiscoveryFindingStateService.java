package com.bablsoft.accessflow.discovery.internal;

import com.bablsoft.accessflow.core.api.CreateDataClassificationTagCommand;
import com.bablsoft.accessflow.core.api.DataClassificationAdminService;
import com.bablsoft.accessflow.core.api.IllegalDataClassificationTagException;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingStatus;
import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryFindingEntity;
import com.bablsoft.accessflow.discovery.internal.persistence.repo.DiscoveryFindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Single-finding decision mutations (AF-623), each in its own transaction so one bad row in a
 * bulk decision never poisons a successful peer (the attestation bulk pattern).
 */
@Service
@RequiredArgsConstructor
@Slf4j
class DiscoveryFindingStateService {

    private final DiscoveryFindingRepository findingRepository;
    private final DataClassificationAdminService dataClassificationAdminService;
    private final Clock clock;

    /** Outcome of a single confirm: the finding plus whether the tag already existed. */
    record ConfirmOutcome(DiscoveryFindingEntity finding, boolean tagConflict) {
    }

    /**
     * Applies the classification tag through the AF-447 service (deriving masking for the column)
     * and marks the finding CONFIRMED. A pre-existing tag ({@link
     * IllegalDataClassificationTagException}) still confirms the finding — the worklist clears —
     * but is reported as a conflict.
     */
    @Transactional
    ConfirmOutcome confirm(DiscoveryFindingEntity finding, UUID actorId) {
        var tagConflict = false;
        try {
            dataClassificationAdminService.create(finding.getDatasourceId(),
                    finding.getOrganizationId(), new CreateDataClassificationTagCommand(
                            qualifiedTable(finding), finding.getColumnName(),
                            List.of(finding.getClassification()), confirmNote(finding), true));
        } catch (IllegalDataClassificationTagException ex) {
            log.info("Classification tag already exists for {}.{} ({}); confirming finding {} without a new tag",
                    qualifiedTable(finding), finding.getColumnName(), finding.getClassification(),
                    finding.getId());
            tagConflict = true;
        }
        decide(finding, DiscoveryFindingStatus.CONFIRMED, actorId);
        return new ConfirmOutcome(finding, tagConflict);
    }

    @Transactional
    DiscoveryFindingEntity dismiss(DiscoveryFindingEntity finding, UUID actorId) {
        decide(finding, DiscoveryFindingStatus.DISMISSED, actorId);
        return finding;
    }

    private void decide(DiscoveryFindingEntity finding, DiscoveryFindingStatus status,
                        UUID actorId) {
        finding.setStatus(status);
        finding.setDecidedBy(actorId);
        finding.setDecidedAt(clock.instant());
        findingRepository.save(finding);
    }

    private static String qualifiedTable(DiscoveryFindingEntity finding) {
        return finding.getSchemaName() == null ? finding.getTableName()
                : finding.getSchemaName() + "." + finding.getTableName();
    }

    // Stored free-text note (like manual tag notes) — deliberately not localized.
    private static String confirmNote(DiscoveryFindingEntity finding) {
        return "Confirmed from discovery finding (" + finding.getDetector() + ", "
                + finding.getConfidence() + "% confidence)";
    }
}
