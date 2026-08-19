package com.bablsoft.accessflow.compliance.api;

import java.util.UUID;

/**
 * Policy decisions for result-set egress (#626). {@code decide} is the pure policy computation —
 * it performs NO caller-visibility check (the export endpoint layers "QUERY_ADMIN or submitter"
 * on top; the email-attachment path has already established that the recipient receives this
 * query's notification). Consumers outside the export endpoint (the notifications module's
 * results-CSV attachment) call {@code decide} per recipient and, after actually emitting the
 * bytes, {@code recordAttachmentExport} — which writes the {@code RESULT_EXPORTED} audit row
 * (best-effort, attributed to the recipient) and raises the sensitive-export notification when
 * classified columns were present.
 */
public interface ResultExportGovernanceService {

    /**
     * The effective export decision for {@code requesterUserId} on {@code queryRequestId}'s
     * persisted result. Fails closed (deny) when the query has no execution snapshot — without
     * it neither the datasource's policies nor the classifications can be resolved, and an
     * unverifiable egress must not be an allowed one.
     */
    ExportDecision decide(UUID organizationId, UUID queryRequestId, UUID requesterUserId);

    /**
     * Records that a results-CSV email attachment for {@code queryRequestId} was sent to
     * {@code recipientUserId}: writes a {@code RESULT_EXPORTED} audit row with
     * {@code trigger=email_attachment} (best-effort — a failed audit write is logged, never
     * thrown, since the mail is already gone) and publishes the sensitive-export event when
     * {@code decision.classificationsPresent()} is non-empty.
     */
    void recordAttachmentExport(UUID organizationId, UUID queryRequestId, UUID recipientUserId,
                                String recipientEmail, ExportDecision decision, long rowCount,
                                boolean truncated);
}
