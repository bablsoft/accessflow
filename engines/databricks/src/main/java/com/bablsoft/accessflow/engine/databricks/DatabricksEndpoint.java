package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.EngineMessages;

import java.net.URI;

/**
 * The resolved Statement Execution API endpoint for one Databricks datasource: the workspace base
 * URL and the SQL warehouse id. {@code descriptor.host()} is the workspace host
 * ({@code adb-….azuredatabricks.net} / {@code dbc-….cloud.databricks.com}) and
 * {@code descriptor.jdbcUrlOverride()} is <strong>required</strong> — either the bare warehouse
 * HTTP path ({@code /sql/1.0/warehouses/<id>}, whose last segment is the warehouse id, giving
 * {@code https://<host>} as the base URL) or a full {@code http(s)://host/sql/1.0/warehouses/<id>}
 * URL whose scheme + authority become the base URL (this form is also the stub-server test hook).
 * Anything malformed resolves to an {@link IllegalArgumentException} carrying the localized
 * {@code error.databricks.warehouse_path_invalid} message; callers translate it onto their path's
 * exception type.
 *
 * @param baseUrl     scheme + authority of the workspace, no trailing slash
 * @param warehouseId the SQL warehouse id submitted as {@code warehouse_id}
 */
record DatabricksEndpoint(String baseUrl, String warehouseId) {

    static DatabricksEndpoint resolve(DatasourceConnectionDescriptor descriptor,
                                      EngineMessages messages) {
        var override = descriptor.jdbcUrlOverride();
        if (override == null || override.isBlank()) {
            throw invalid(messages);
        }
        override = override.strip();
        if (override.startsWith("http://") || override.startsWith("https://")) {
            URI uri;
            try {
                uri = URI.create(override);
            } catch (IllegalArgumentException e) {
                throw invalid(messages);
            }
            if (uri.getHost() == null || uri.getPath() == null) {
                throw invalid(messages);
            }
            return new DatabricksEndpoint(uri.getScheme() + "://" + uri.getRawAuthority(),
                    warehouseIdFrom(uri.getPath(), messages));
        }
        if (override.startsWith("/")) {
            var host = descriptor.host();
            if (host == null || host.isBlank()) {
                throw invalid(messages);
            }
            return new DatabricksEndpoint("https://" + host.strip(),
                    warehouseIdFrom(override, messages));
        }
        throw invalid(messages);
    }

    /** The last path segment, required to directly follow a {@code warehouses} segment. */
    private static String warehouseIdFrom(String path, EngineMessages messages) {
        var segments = path.split("/");
        var cleaned = new java.util.ArrayList<String>();
        for (var segment : segments) {
            if (!segment.isBlank()) {
                cleaned.add(segment);
            }
        }
        if (cleaned.size() < 2 || !"warehouses".equals(cleaned.get(cleaned.size() - 2))) {
            throw invalid(messages);
        }
        return cleaned.get(cleaned.size() - 1);
    }

    private static IllegalArgumentException invalid(EngineMessages messages) {
        return new IllegalArgumentException(
                messages.get("error.databricks.warehouse_path_invalid"));
    }
}
