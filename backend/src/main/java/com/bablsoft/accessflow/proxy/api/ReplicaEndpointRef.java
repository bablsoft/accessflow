package com.bablsoft.accessflow.proxy.api;

import java.util.UUID;

/**
 * Lightweight reference to one read-replica endpoint of a datasource (AF-457). {@code label} is a
 * redacted display form of the endpoint's JDBC URL (host/port only — never credentials or query
 * params), safe for logs, audit metadata, and admin health views.
 */
public record ReplicaEndpointRef(UUID endpointId, String label) {
}
