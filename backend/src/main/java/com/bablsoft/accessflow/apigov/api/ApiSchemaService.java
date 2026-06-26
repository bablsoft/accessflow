package com.bablsoft.accessflow.apigov.api;

import java.util.List;
import java.util.UUID;

/**
 * Manages uploaded API schema documents and the normalized operation catalog derived from them.
 * Uploading parses the document immediately (rejecting invalid input with
 * {@link ApiSchemaParseException}) and caches the catalog on the schema row.
 */
public interface ApiSchemaService {

    ApiSchemaView upload(UUID connectorId, UUID organizationId, ApiSchemaType schemaType,
                         String rawContent, String sourceUrl);

    List<ApiSchemaView> list(UUID connectorId, UUID organizationId);

    void delete(UUID connectorId, UUID organizationId, UUID schemaId);

    /** The operation catalog from the connector's most recent schema (empty if none). */
    List<ApiOperation> listOperations(UUID connectorId, UUID organizationId);
}
