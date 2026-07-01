package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.lifecycle.api.ErasureCondition;
import com.bablsoft.accessflow.lifecycle.api.ErasureConditionSet;
import com.bablsoft.accessflow.lifecycle.api.InvalidErasureConfigException;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Save/submit-time validation (AF-519) for the shared erasure configuration: structured conditions,
 * the raw-WHERE escape hatch, and (for policies) the cron schedule. Rejects invalid config with
 * {@link InvalidErasureConfigException} (mapped to 400/422 by the lifecycle web layer) so a user
 * sees the error immediately rather than at scheduled-execution time. Conditions and raw WHERE are
 * SQL constructs, so they are only accepted for SQL-family (JDBC) datasources; NoSQL erasure
 * conditions are out of scope (deferred). The {@link ErasurePredicateCompiler} re-validates
 * defensively at execution.
 */
@Component
@RequiredArgsConstructor
class ErasureConditionValidator {

    private static final Set<DbType> SQL_FAMILY = EnumSet.of(
            DbType.POSTGRESQL, DbType.MYSQL, DbType.MARIADB, DbType.ORACLE, DbType.MSSQL, DbType.CUSTOM);
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private final DatasourceLookupService datasourceLookupService;
    private final SqlParserService sqlParserService;

    /**
     * @param datasourceId the target datasource (its SQL family gates conditions/raw WHERE)
     * @param targetTable  the table conditions/raw WHERE apply to (used to parse-validate raw WHERE)
     * @param conditions   nullable structured conditions
     * @param rawWhere      nullable raw WHERE escape hatch
     * @param cronSchedule  nullable cron schedule (policies only; requests pass {@code null})
     */
    void validate(UUID datasourceId, String targetTable, ErasureConditionSet conditions,
                  String rawWhere, String cronSchedule) {
        boolean hasStructured = conditions != null && !conditions.isEmpty();
        boolean hasRawWhere = rawWhere != null && !rawWhere.isBlank();

        if ((hasStructured || hasRawWhere) && !isSqlFamily(datasourceId)) {
            throw new InvalidErasureConfigException(
                    InvalidErasureConfigException.Reason.UNSUPPORTED_DATASOURCE);
        }
        if (hasStructured) {
            for (ErasureCondition c : conditions.conditions()) {
                validateCondition(c);
            }
        }
        if (hasRawWhere) {
            var table = targetTable == null || targetTable.isBlank() ? "t" : targetTable;
            try {
                sqlParserService.parse("SELECT 1 FROM " + table + " WHERE " + rawWhere);
            } catch (InvalidSqlException ex) {
                throw new InvalidErasureConfigException(
                        InvalidErasureConfigException.Reason.INVALID_RAW_WHERE);
            }
        }
        if (cronSchedule != null && !cronSchedule.isBlank()) {
            try {
                CronExpression.parse(cronSchedule.trim());
            } catch (IllegalArgumentException ex) {
                throw new InvalidErasureConfigException(
                        InvalidErasureConfigException.Reason.INVALID_CRON);
            }
        }
    }

    private void validateCondition(ErasureCondition c) {
        if (c.column() == null || c.column().isBlank() || !IDENTIFIER.matcher(c.column()).matches()) {
            throw new InvalidErasureConfigException(
                    InvalidErasureConfigException.Reason.CONDITION_COLUMN_REQUIRED);
        }
        var op = c.operator();
        boolean bad = op == RowSecurityOperator.IS_NULL
                ? !c.values().isEmpty()
                : op.isMultiValue() ? c.values().isEmpty() : c.values().size() != 1;
        if (bad) {
            throw new InvalidErasureConfigException(
                    InvalidErasureConfigException.Reason.CONDITION_VALUE_ARITY);
        }
    }

    private boolean isSqlFamily(UUID datasourceId) {
        return datasourceLookupService.findById(datasourceId)
                .map(d -> SQL_FAMILY.contains(d.dbType()))
                .orElse(true); // unknown datasource is surfaced elsewhere; don't over-reject here
    }
}
