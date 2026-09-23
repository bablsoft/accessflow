package com.bablsoft.accessflow.core.api;

/**
 * Computes a stable, order-independent SHA-256 fingerprint of a {@link DatabaseSchemaView} (#881).
 *
 * <p>Replaces the narrower {@code workflow.internal.SchemaHasher}, which canonicalized only
 * {@code name:type} and was therefore blind to a nullability flip, a primary-key change and every
 * foreign key. This one covers all four {@link DatabaseSchemaView.Column} fields plus foreign keys,
 * so two schemas that differ in any of them fingerprint differently.
 *
 * <p>Its consumers are query snapshots and replay, which record the source and target hashes side by
 * side. Those compare <em>across</em> datasources, so the canonical form is case-insensitive: driver
 * case conventions differ by engine and by server setting, and two identical schemas must not differ
 * because one server was configured differently.
 */
public interface SchemaFingerprintService {

    /**
     * Returns a 64-character lowercase hex SHA-256 of the canonical schema form. Never {@code null};
     * a {@code null} or empty schema hashes to one stable value.
     */
    String fingerprint(DatabaseSchemaView schema);
}
