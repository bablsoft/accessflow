package com.bablsoft.accessflow.audit.api;

import java.util.UUID;

/**
 * Per-organization outcome of an all-organizations audit-chain verification sweep
 * (AF-458 — post-restore integrity check).
 */
public record AuditChainVerificationSummary(UUID organizationId, AuditLogVerificationResult result) {
}
