package com.bablsoft.accessflow.discovery.internal;

/**
 * Identity of a scanned table, used to decide which findings a run is allowed to age (AF-659).
 *
 * <p>{@code schemaName} is normalised from {@code null} to the empty string, matching the
 * {@code COALESCE(schema_name, '')} semantics of the {@code uq_discovery_finding} unique index —
 * engines without a schema concept persist a null schema on the finding but reach here through
 * the same key. The table name is <strong>not</strong> case-folded: findings persist the spelling
 * introspection returned, and folding would over-match on case-sensitive engines.
 */
record DiscoveryTableKey(String schemaName, String tableName) {

    static DiscoveryTableKey of(String schemaName, String tableName) {
        return new DiscoveryTableKey(schemaName == null ? "" : schemaName, tableName);
    }
}
