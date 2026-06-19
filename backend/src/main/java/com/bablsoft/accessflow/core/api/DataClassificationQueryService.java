package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

/**
 * Read-only access to a datasource's classification tags for non-admin consumers — notably the AI
 * analyzer, which raises a query's risk score when it references a tagged object. Returns an empty
 * list when the datasource has no tags. Does not validate the datasource-in-organization invariant
 * (callers already hold an organization-scoped datasource id).
 */
public interface DataClassificationQueryService {

    List<DataClassificationTagView> findByDatasource(UUID datasourceId, UUID organizationId);
}
