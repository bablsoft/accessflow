package com.bablsoft.accessflow.compliance.api;

import java.util.UUID;

/**
 * The query does not exist in the caller's organization, the caller may not see it, or it has no
 * persisted result snapshot — indistinguishable by design (information hiding, mirroring
 * {@code GET /queries/{id}/results}).
 */
public final class ResultExportNotFoundException extends ResultExportException {

    public ResultExportNotFoundException(UUID queryRequestId) {
        super("Query result not found: " + queryRequestId);
    }
}
