package com.bablsoft.accessflow.attestation.api;

import java.util.UUID;

/**
 * Produces a CSV evidence record for a campaign — one row per item with the subject, the attested
 * permission shape, the decision, who decided, and when. Used to satisfy SOC2 / ISO 27001
 * access-review evidence requirements.
 */
public interface AttestationEvidenceExportService {

    EvidenceExport export(UUID campaignId, UUID organizationId);

    /**
     * @param content   UTF-8 CSV bytes
     * @param filename  suggested download filename
     * @param rowCount  number of item rows written
     * @param truncated true when the campaign had more items than the configured export cap
     */
    record EvidenceExport(byte[] content, String filename, int rowCount, boolean truncated) {
    }
}
