package com.bablsoft.accessflow.core.api;

/** Which configuration supplied the binding bytes-scanned cap (#941). */
public enum BytesScannedCapSource {
    /** {@code datasources.max_bytes_scanned_per_query}. */
    DATASOURCE,
    /** A {@code bytes_scanned_limit_override} on the submitter's direct or group grant. */
    GRANT
}
