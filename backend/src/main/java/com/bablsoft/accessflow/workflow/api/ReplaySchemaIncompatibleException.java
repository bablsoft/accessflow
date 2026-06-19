package com.bablsoft.accessflow.workflow.api;

import com.bablsoft.accessflow.core.api.DbType;

import java.util.List;
import java.util.UUID;

/**
 * Thrown when a replay target datasource is incompatible with the snapshotted query (AF-449). Carries a
 * {@link Reason} discriminator so the web layer can resolve the right localized message; the offending
 * details (db-type pair, or the list of missing tables) are exposed for the {@code ProblemDetail}. Maps
 * to HTTP 422. No user-facing English text lives here — the controller advice resolves the message via
 * {@code MessageSource}.
 */
public final class ReplaySchemaIncompatibleException extends RuntimeException {

    public enum Reason {
        DB_TYPE_MISMATCH,
        MISSING_TABLES,
        TARGET_SCHEMA_UNAVAILABLE
    }

    private final UUID targetDatasourceId;
    private final Reason reason;
    private final DbType expectedDbType;
    private final DbType actualDbType;
    private final List<String> missingTables;

    private ReplaySchemaIncompatibleException(UUID targetDatasourceId, Reason reason,
                                              DbType expectedDbType, DbType actualDbType,
                                              List<String> missingTables, String message) {
        super(message);
        this.targetDatasourceId = targetDatasourceId;
        this.reason = reason;
        this.expectedDbType = expectedDbType;
        this.actualDbType = actualDbType;
        this.missingTables = missingTables == null ? List.of() : List.copyOf(missingTables);
    }

    public static ReplaySchemaIncompatibleException dbTypeMismatch(UUID targetDatasourceId,
                                                                   DbType expected, DbType actual) {
        return new ReplaySchemaIncompatibleException(targetDatasourceId, Reason.DB_TYPE_MISMATCH,
                expected, actual, List.of(),
                "Cannot replay a " + expected + " query against a " + actual + " datasource");
    }

    public static ReplaySchemaIncompatibleException missingTables(UUID targetDatasourceId,
                                                                  List<String> missingTables) {
        return new ReplaySchemaIncompatibleException(targetDatasourceId, Reason.MISSING_TABLES,
                null, null, missingTables,
                "Target datasource is missing tables required by the query: " + missingTables);
    }

    public static ReplaySchemaIncompatibleException targetSchemaUnavailable(UUID targetDatasourceId) {
        return new ReplaySchemaIncompatibleException(targetDatasourceId, Reason.TARGET_SCHEMA_UNAVAILABLE,
                null, null, List.of(),
                "Could not introspect the target datasource schema to verify compatibility");
    }

    public UUID targetDatasourceId() {
        return targetDatasourceId;
    }

    public Reason reason() {
        return reason;
    }

    public DbType expectedDbType() {
        return expectedDbType;
    }

    public DbType actualDbType() {
        return actualDbType;
    }

    public List<String> missingTables() {
        return missingTables;
    }
}
