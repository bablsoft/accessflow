package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.proxy.api.DatasourceUnavailableException;
import com.bablsoft.accessflow.core.api.QueryEngineCatalog;
import com.bablsoft.accessflow.core.api.QueryEngineExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
class DefaultQueryExecutor implements QueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultQueryExecutor.class);

    private final RoutingDataSourceResolver routingResolver;
    private final DatasourceLookupService datasourceLookupService;
    private final ProxyPoolProperties properties;
    private final JdbcResultRowMapper rowMapper;
    private final SqlExceptionTranslator sqlExceptionTranslator;
    private final RowSecurityRewriter rowSecurityRewriter;
    private final QueryEngineCatalog engineCatalog;
    private final Clock clock;
    private final MessageSource messageSource;

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    @Override
    public QueryExecutionResult execute(QueryExecutionRequest request) {
        var descriptor = datasourceLookupService.findById(request.datasourceId())
                .orElseThrow(() -> new DatasourceUnavailableException(
                        msg("error.datasource_unavailable_not_found")));
        var execProps = properties.execution();
        int effectiveMaxRows = clampMaxRows(request.maxRowsOverride(),
                descriptor.maxRowsPerQuery(), execProps.maxRows());
        Duration effectiveTimeout = request.statementTimeoutOverride() != null
                ? request.statementTimeoutOverride()
                : execProps.statementTimeout();

        if (descriptor.dbType() == DbType.MONGODB) {
            return engineCatalog.engineFor(DbType.MONGODB).execute(new QueryEngineExecutionRequest(
                    request, descriptor, effectiveMaxRows, effectiveTimeout));
        }

        Instant start = clock.instant();
        if (request.transactional()) {
            return executeTransactional(request, effectiveTimeout, start);
        }
        var rewrite = rowSecurityRewriter.rewrite(request.sql(), request.rowSecurityPredicates());
        try (Connection connection = routingResolver.acquire(request.datasourceId(),
                request.queryType())) {
            connection.setReadOnly(request.queryType() == QueryType.SELECT);
            try (PreparedStatement statement = connection.prepareStatement(rewrite.sql())) {
                statement.setQueryTimeout(toTimeoutSeconds(effectiveTimeout));
                statement.setFetchSize(Math.min(effectiveMaxRows + 1, execProps.defaultFetchSize()));
                bind(statement, rewrite.binds());
                if (request.queryType() == QueryType.SELECT) {
                    return runSelect(statement, effectiveMaxRows, descriptor.dbType(), start,
                            request.restrictedColumns(), request.columnMasks(),
                            rewrite.appliedPolicyIds());
                }
                return runUpdate(statement, start, rewrite.appliedPolicyIds());
            }
        } catch (SQLException ex) {
            log.debug("SQL execution failed for datasource {}: {}",
                    request.datasourceId(), ex.getMessage());
            throw sqlExceptionTranslator.translate(ex, effectiveTimeout, LocaleContextHolder.getLocale());
        }
    }

    private QueryExecutionResult executeTransactional(QueryExecutionRequest request,
                                                      Duration effectiveTimeout, Instant start) {
        var appliedPolicyIds = new java.util.LinkedHashSet<java.util.UUID>();
        try (Connection connection = routingResolver.acquire(request.datasourceId(),
                QueryType.OTHER)) {
            connection.setReadOnly(false);
            connection.setAutoCommit(false);
            long totalAffected = 0;
            try {
                for (String stmtSql : request.statements()) {
                    var rewrite = rowSecurityRewriter.rewrite(stmtSql,
                            request.rowSecurityPredicates());
                    appliedPolicyIds.addAll(rewrite.appliedPolicyIds());
                    try (PreparedStatement statement = connection.prepareStatement(rewrite.sql())) {
                        statement.setQueryTimeout(toTimeoutSeconds(effectiveTimeout));
                        bind(statement, rewrite.binds());
                        totalAffected += statement.executeLargeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException ex) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackEx) {
                    ex.addSuppressed(rollbackEx);
                }
                throw ex;
            }
            return new UpdateExecutionResult(totalAffected, durationSince(start), appliedPolicyIds);
        } catch (SQLException ex) {
            log.debug("Transactional SQL execution failed for datasource {}: {}",
                    request.datasourceId(), ex.getMessage());
            throw sqlExceptionTranslator.translate(ex, effectiveTimeout, LocaleContextHolder.getLocale());
        }
    }

    private QueryExecutionResult runSelect(PreparedStatement statement, int effectiveMaxRows,
                                           DbType dbType, Instant start,
                                           List<String> restrictedColumns,
                                           List<ColumnMaskDirective> columnMasks,
                                           java.util.Set<java.util.UUID> appliedRowSecurityPolicyIds)
            throws SQLException {
        statement.setMaxRows(effectiveMaxRows + 1);
        try (var resultSet = statement.executeQuery()) {
            SelectExecutionResult result = rowMapper.materialize(resultSet, effectiveMaxRows, dbType,
                    durationSince(start), restrictedColumns, columnMasks);
            return appliedRowSecurityPolicyIds.isEmpty()
                    ? result
                    : result.withRowSecurityPolicyIds(appliedRowSecurityPolicyIds);
        }
    }

    private UpdateExecutionResult runUpdate(PreparedStatement statement, Instant start,
                                            java.util.Set<java.util.UUID> appliedRowSecurityPolicyIds)
            throws SQLException {
        long affected = statement.executeLargeUpdate();
        return new UpdateExecutionResult(affected, durationSince(start), appliedRowSecurityPolicyIds);
    }

    private static void bind(PreparedStatement statement, List<Object> binds) throws SQLException {
        for (int i = 0; i < binds.size(); i++) {
            statement.setObject(i + 1, binds.get(i));
        }
    }

    private Duration durationSince(Instant start) {
        return Duration.between(start, clock.instant());
    }

    private static int clampMaxRows(Integer override, int datasourceCap, int globalCap) {
        int candidate = override != null ? override : datasourceCap;
        return Math.min(candidate, globalCap);
    }

    private static int toTimeoutSeconds(Duration timeout) {
        long seconds = timeout.toSeconds();
        if (seconds <= 0) {
            return 1;
        }
        return (int) Math.min(seconds, Integer.MAX_VALUE);
    }
}
