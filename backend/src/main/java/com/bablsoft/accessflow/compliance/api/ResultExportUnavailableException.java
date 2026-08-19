package com.bablsoft.accessflow.compliance.api;

import java.util.UUID;

/** The query executed but is not a SELECT — there is no result set to export. */
public final class ResultExportUnavailableException extends ResultExportException {

    public ResultExportUnavailableException(UUID queryRequestId) {
        super("Query is not a SELECT: " + queryRequestId);
    }
}
