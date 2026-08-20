package com.bablsoft.accessflow.audit.internal.sink;

import java.time.Instant;
import java.util.UUID;

/**
 * The canonical exported form of one {@code audit_log} row (#628). {@code metadataJson} is the
 * raw stored JSONB (embedded as an object on the wire, not a string); the hashes are lowercase
 * hex (the audit CSV-export convention) so any exported window is independently
 * chain-verifiable against the in-DB HMAC chain.
 */
public record AuditExportEvent(
        UUID id,
        UUID organizationId,
        UUID actorId,
        String action,
        String resourceType,
        UUID resourceId,
        String metadataJson,
        String ipAddress,
        String userAgent,
        Instant createdAt,
        String previousHash,
        String currentHash) {
}
