package com.bablsoft.accessflow.apigov.api;

import java.util.List;
import java.util.UUID;

/**
 * Manages uploaded API schema documents and the normalized operation catalog derived from them.
 * Uploading parses the document immediately (rejecting invalid input with
 * {@link ApiSchemaParseException}) and caches the full catalog on the schema row; an
 * {@link OperationFilter} declared at import time (or edited later) narrows the governed catalog
 * returned by {@link #listOperations}.
 */
public interface ApiSchemaService {

    ApiSchemaView upload(UUID connectorId, UUID organizationId, ApiSchemaType schemaType,
                         String rawContent, String sourceUrl, OperationFilter filter);

    List<ApiSchemaView> list(UUID connectorId, UUID organizationId);

    void delete(UUID connectorId, UUID organizationId, UUID schemaId);

    /** Re-applies a new {@link OperationFilter} to an existing schema without re-uploading it. */
    ApiSchemaView updateFilter(UUID connectorId, UUID organizationId, UUID schemaId,
                               OperationFilter filter);

    /** Dry-runs {@code filter} against a parsed document without persisting anything. */
    OperationFilterPreview previewFilter(UUID connectorId, UUID organizationId, ApiSchemaType schemaType,
                                         String rawContent, String sourceUrl, OperationFilter filter);

    /** The governed operation catalog from the connector's most recent schema (empty if none). */
    List<ApiOperation> listOperations(UUID connectorId, UUID organizationId);
}
