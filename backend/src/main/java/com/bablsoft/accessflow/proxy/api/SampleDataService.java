package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.SelectExecutionResult;

import java.util.UUID;

/**
 * Reads a bounded, governance-applied sample of rows from a single table/collection (issue AF-443).
 *
 * <p>This is an ad-hoc, review-bypassing read that still applies <em>full</em> governance: it does
 * not create a {@code query_request}, but it resolves the caller's row-security predicates and
 * column masks and runs through the same executor path as a normal query, so a masked column never
 * returns a raw value and row-level security filters the sample. The configured row cap and
 * statement timeout ({@code ACCESSFLOW_PROXY_EXECUTION_*}) are enforced.
 *
 * <p>Authorization mirrors schema introspection but additionally requires read capability: ADMINs
 * may sample any datasource in their organization; other callers need a permission row with
 * {@code can_read} and the target schema/table within their {@code allowed_schemas}/
 * {@code allowed_tables}. A target absent from the introspected schema (or outside the allow-list)
 * raises {@link com.bablsoft.accessflow.core.api.TableNotFoundException} (HTTP 404).
 */
public interface SampleDataService {

    SelectExecutionResult sample(UUID datasourceId, UUID organizationId, UUID userId,
                                 boolean isAdmin, String schema, String table, int limit);
}
