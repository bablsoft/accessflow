package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

/** A refresh, or a refresh interval, was requested for a schema that has no {@code sourceUrl}. */
public class ApiSchemaNotRefreshableException extends ApiGovException {

    public ApiSchemaNotRefreshableException(UUID schemaId) {
        super("API schema has no source URL to refresh from: " + schemaId);
    }
}
