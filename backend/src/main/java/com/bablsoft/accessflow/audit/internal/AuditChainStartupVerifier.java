package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.api.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Post-restore integrity sweep (AF-458). When {@code accessflow.audit.verify-chain-on-startup}
 * (env {@code ACCESSFLOW_AUDIT_VERIFY_CHAIN_ON_STARTUP}) is {@code true}, walks every
 * organization's audit-log HMAC chain once the application is ready and logs a per-organization
 * summary — INFO when intact, ERROR with the first bad row when tampered or broken. Verification
 * never fails startup; the disaster-recovery runbook (docs/09-deployment.md) tells operators to
 * enable the flag for the first boot after a restore and inspect these log lines.
 *
 * <p>Verification only succeeds when the deployment runs the same {@code AUDIT_HMAC_KEY} (or the
 * same {@code ENCRYPTION_KEY}, when the HMAC key is HKDF-derived) as when the rows were written.
 */
@Component
@ConditionalOnProperty(prefix = "accessflow.audit", name = "verify-chain-on-startup",
        havingValue = "true")
@RequiredArgsConstructor
class AuditChainStartupVerifier {

    private static final Logger log = LoggerFactory.getLogger(AuditChainStartupVerifier.class);

    private final AuditLogService auditLogService;

    @EventListener(ApplicationReadyEvent.class)
    void verifyOnStartup() {
        log.info("Audit chain startup verification enabled - walking every organization's hash chain");
        try {
            var summaries = auditLogService.verifyAllOrganizations();
            if (summaries.isEmpty()) {
                log.info("Audit chain verification complete: no audit rows found");
                return;
            }
            long broken = 0;
            for (var summary : summaries) {
                var result = summary.result();
                if (result.ok()) {
                    log.info("Audit chain OK for organization {} ({} rows checked)",
                            summary.organizationId(), result.rowsChecked());
                } else {
                    broken++;
                    log.error("Audit chain BROKEN for organization {}: {} at row {} (created_at {}, "
                                    + "{} rows verified before failure)",
                            summary.organizationId(), result.firstBadReason(), result.firstBadRowId(),
                            result.firstBadCreatedAt(), result.rowsChecked());
                }
            }
            if (broken == 0) {
                log.info("Audit chain verification complete: {} organization(s) intact",
                        summaries.size());
            } else {
                log.error("Audit chain verification complete: {} of {} organization(s) BROKEN - "
                                + "check AUDIT_HMAC_KEY / ENCRYPTION_KEY match the backed-up deployment "
                                + "before suspecting tampering",
                        broken, summaries.size());
            }
        } catch (RuntimeException ex) {
            log.error("Audit chain startup verification failed to run", ex);
        }
    }
}
