package com.bablsoft.accessflow.schemachange.internal.web;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record RequestSchemaDriftScanRequest(
        @NotNull(message = "{validation.schema_drift_scan.environment_id.required}")
        UUID environmentId) {
}
