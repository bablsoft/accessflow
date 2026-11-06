package com.bablsoft.accessflow.apigov.api;

/** The schema after a revision was accepted, plus the accepted revision. */
public record AcceptedApiSchemaRevision(ApiSchemaView schema, ApiSchemaRevisionView revision) {
}
