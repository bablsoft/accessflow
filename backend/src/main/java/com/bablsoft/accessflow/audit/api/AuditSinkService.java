package com.bablsoft.accessflow.audit.api;

import java.util.List;
import java.util.UUID;

/**
 * Admin CRUD and test dispatch for external audit sinks (#628). Draining happens in the
 * module-internal scheduled job; this interface only manages sink configuration and health.
 */
public interface AuditSinkService {

    /** All sinks of one organization, with masked config and current delivery health. */
    List<AuditSinkView> list(UUID organizationId);

    AuditSinkView create(CreateAuditSinkCommand command);

    AuditSinkView update(UUID id, UUID organizationId, UpdateAuditSinkCommand command);

    void delete(UUID id, UUID organizationId);

    /**
     * Synchronously delivers a single synthetic test event through the sink's deliverer.
     * Throws {@link AuditSinkTestFailedException} when the destination rejects the delivery
     * or is unreachable.
     */
    void sendTest(UUID id, UUID organizationId);
}
