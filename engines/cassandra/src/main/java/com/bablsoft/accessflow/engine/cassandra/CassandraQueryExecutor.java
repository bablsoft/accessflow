package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import com.bablsoft.accessflow.engine.cassandra.CassandraResultMapper.CqlColumn;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.cql.ColumnDefinitions;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.SimpleStatementBuilder;
import com.datastax.oss.driver.api.core.metadata.Metadata;
import com.datastax.oss.driver.api.core.metadata.schema.KeyspaceMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Executes a CQL statement for a {@code CASSANDRA} / {@code SCYLLADB} datasource — the wide-column
 * analogue of the host's JDBC execution path. Re-parses the submitted query, resolves the target
 * table's partition/clustering key columns from the live session metadata, applies row-security
 * predicates (rewritten CQL + named parameters, never concatenated values), and runs the statement.
 * SELECTs page through the driver capped at {@code maxRows + 1} to detect truncation without
 * buffering unbounded results, then map through {@link CassandraResultMapper} (which applies column
 * masking). DML returns 1 affected row (0 for a lightweight transaction whose condition did not
 * match); DDL returns 0. The host-computed statement timeout is applied per statement.
 */
class CassandraQueryExecutor {

    private final CassandraSessionManager sessionManager;
    private final CqlQueryParser parser;
    private final CassandraRowSecurityApplier rowSecurityApplier;
    private final CassandraResultMapper resultMapper;
    private final CassandraExceptionTranslator exceptionTranslator;
    private final Clock clock;

    CassandraQueryExecutor(CassandraSessionManager sessionManager, CqlQueryParser parser,
                           CassandraRowSecurityApplier rowSecurityApplier,
                           CassandraResultMapper resultMapper,
                           CassandraExceptionTranslator exceptionTranslator, Clock clock) {
        this.sessionManager = sessionManager;
        this.parser = parser;
        this.rowSecurityApplier = rowSecurityApplier;
        this.resultMapper = resultMapper;
        this.exceptionTranslator = exceptionTranslator;
        this.clock = clock;
    }

    QueryExecutionResult execute(QueryExecutionRequest request,
                                 DatasourceConnectionDescriptor descriptor, int maxRows,
                                 Duration timeout) {
        var start = clock.instant();
        var statement = parser.parseStatement(request.sql());
        var session = sessionManager.session(descriptor);
        var keyColumns = keyColumns(session, statement.target(), descriptor);
        var applied = rowSecurityApplier.apply(statement, request.rowSecurityPredicates(), keyColumns);
        try {
            var resultSet = session.execute(
                    build(applied, statement.kind().isRead(), maxRows, timeout));
            if (statement.kind().isRead()) {
                var columns = columns(resultSet.getColumnDefinitions());
                var rows = fetchRows(resultSet, maxRows);
                var result = resultMapper.materialize(columns, rows, maxRows, durationSince(start),
                        request.restrictedColumns(), request.columnMasks());
                return applied.appliedPolicyIds().isEmpty()
                        ? result
                        : result.withRowSecurityPolicyIds(applied.appliedPolicyIds());
            }
            long affected = statement.kind() == CqlStatementKind.DDL
                    ? 0
                    : (resultSet.wasApplied() ? 1 : 0);
            return new UpdateExecutionResult(affected, durationSince(start),
                    applied.appliedPolicyIds());
        } catch (DriverException ex) {
            throw exceptionTranslator.translate(ex, timeout);
        }
    }

    private static SimpleStatement build(CassandraRowSecurityApplier.Applied applied, boolean read,
                                         int maxRows, Duration timeout) {
        SimpleStatementBuilder builder = SimpleStatement.builder(applied.cql());
        for (var entry : applied.parameters().entrySet()) {
            builder.addNamedValue(CqlIdentifier.fromInternal(entry.getKey()), entry.getValue());
        }
        builder.setTimeout(timeout);
        if (read) {
            builder.setPageSize(maxRows + 1);
        }
        return builder.build();
    }

    /** Collect up to {@code maxRows + 1} rows (the truncation sentinel) from the first page(s). */
    private static List<List<Object>> fetchRows(ResultSet resultSet, int maxRows) {
        int limit = maxRows + 1;
        int columnCount = resultSet.getColumnDefinitions().size();
        var rows = new ArrayList<List<Object>>();
        for (Row row : resultSet) {
            rows.add(extractRow(row, columnCount));
            if (rows.size() >= limit) {
                break;
            }
        }
        return rows;
    }

    private static List<Object> extractRow(Row row, int columnCount) {
        var values = new ArrayList<>(columnCount);
        for (int i = 0; i < columnCount; i++) {
            values.add(normalize(row.getObject(i)));
        }
        return values;
    }

    /** Keep JSON-friendly scalars; stringify every richer CQL type (uuid, timestamp, inet, …). */
    private static Object normalize(Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case Number n -> n;
            case Boolean b -> b;
            default -> String.valueOf(value);
        };
    }

    private static List<CqlColumn> columns(ColumnDefinitions definitions) {
        var columns = new ArrayList<CqlColumn>(definitions.size());
        for (int i = 0; i < definitions.size(); i++) {
            var definition = definitions.get(i);
            columns.add(new CqlColumn(definition.getName().asInternal(),
                    definition.getType().asCql(false, true)));
        }
        return columns;
    }

    /** The target table's partition + clustering key columns, or empty when it can't be resolved. */
    private static Set<String> keyColumns(CqlSession session, CqlTableRef target,
                                          DatasourceConnectionDescriptor descriptor) {
        if (target == null) {
            return Set.of();
        }
        var keyspaceName = target.keyspace() != null ? target.keyspace() : descriptor.databaseName();
        if (keyspaceName == null || keyspaceName.isBlank()) {
            return Set.of();
        }
        return lookupKeyspace(session.getMetadata(), keyspaceName)
                .flatMap(keyspace -> lookupTable(keyspace, target.table()))
                .map(CassandraSchemaIntrospector::primaryKeyColumns)
                .orElse(Set.of());
    }

    private static Optional<KeyspaceMetadata> lookupKeyspace(Metadata metadata, String name) {
        var keyspace = metadata.getKeyspace(CqlIdentifier.fromInternal(name));
        if (keyspace.isEmpty()) {
            try {
                keyspace = metadata.getKeyspace(CqlIdentifier.fromCql(name));
            } catch (IllegalArgumentException ignored) {
                // Not a valid CQL identifier — leave empty; the RLS applier fails closed.
            }
        }
        return keyspace;
    }

    private static Optional<TableMetadata> lookupTable(KeyspaceMetadata keyspace, String name) {
        var table = keyspace.getTable(CqlIdentifier.fromInternal(name));
        if (table.isEmpty()) {
            try {
                table = keyspace.getTable(CqlIdentifier.fromCql(name));
            } catch (IllegalArgumentException ignored) {
                // Not a valid CQL identifier — leave empty; the RLS applier fails closed.
            }
        }
        return table;
    }

    private Duration durationSince(Instant start) {
        return Duration.between(start, clock.instant());
    }
}
