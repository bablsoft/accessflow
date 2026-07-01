package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.lifecycle.api.ErasureCondition;
import com.bablsoft.accessflow.lifecycle.api.ErasureConditionSet;
import com.bablsoft.accessflow.lifecycle.api.InvalidErasureConfigException;
import com.bablsoft.accessflow.lifecycle.api.LifecycleSubjectType;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Single source of truth (AF-519) for turning a shared erasure configuration —
 * {@code subject | structured conditions | raw WHERE} (+ an optional retention window) — into the
 * inputs of a governed {@code QueryExecutionRequest}. Used by both erasure-request execution and
 * retention-policy execution so the two paths bind values identically.
 *
 * <ul>
 *   <li><b>Subject</b> ({@code subject_type}+{@code subject_identifier}) → one bound
 *       {@link RowSecurityDirective} ({@code <subjectColumn> = <identifier>}) — byte-for-byte the
 *       pre-AF-519 behavior for backward compatibility.</li>
 *   <li><b>Structured conditions</b> (AND-combined) → one bound {@link RowSecurityDirective} each;
 *       the proxy {@code RowSecurityRewriter} conjoins them and binds every value as a JDBC
 *       parameter — never string-concatenated.</li>
 *   <li><b>Raw WHERE</b> → validated with {@link SqlParserService} (JSqlParser) by parsing
 *       {@code SELECT 1 FROM <table> WHERE <rawWhere>}; returned as a parenthesised clause the caller
 *       inlines into the {@code DELETE} text. It is admin/reviewer-authored SQL, not free user input,
 *       and structured/subject <em>values</em> stay bound.</li>
 *   <li><b>Retention window</b> → an inlined {@code <timestamp_column> < TIMESTAMP '<cutoff>'} clause
 *       (a server-computed UTC constant, mirroring {@code LifecyclePreviewCalculator}).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class ErasurePredicateCompiler {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // Simple [schema.]column identifier — the structured builder's column comes from schema
    // introspection, but validate defensively (it is only ever a bound predicate's left-hand side).
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private final SqlParserService sqlParserService;

    /**
     * @param identityId    row-security {@code policyId} (audit id) — the request or policy id
     * @param table         the target table (validated by the caller)
     * @param subjectType   nullable — legacy subject shape
     * @param subjectIdentifier nullable — legacy subject value
     * @param conditions    nullable — AND-combined structured predicates
     * @param rawWhere      nullable — raw WHERE escape hatch
     * @param timestampColumn nullable — retention timestamp column (with {@code windowCutoff})
     * @param windowCutoff  nullable — rows older than this are eligible (retention only)
     */
    Compiled compile(UUID identityId, String table, LifecycleSubjectType subjectType,
                     String subjectIdentifier, ErasureConditionSet conditions, String rawWhere,
                     String timestampColumn, ZonedDateTime windowCutoff) {
        var directives = new ArrayList<RowSecurityDirective>();

        if (subjectIdentifier != null && !subjectIdentifier.isBlank()) {
            directives.add(new RowSecurityDirective(identityId, table,
                    subjectColumn(subjectType), RowSecurityOperator.EQUALS,
                    List.of(subjectIdentifier)));
        }

        if (conditions != null) {
            for (ErasureCondition c : conditions.conditions()) {
                directives.add(toDirective(identityId, table, c));
            }
        }

        var whereParts = new ArrayList<String>();
        if (windowCutoff != null && timestampColumn != null && !timestampColumn.isBlank()) {
            whereParts.add(timestampColumn + " < TIMESTAMP '" + TS.format(windowCutoff) + "'");
        }
        if (rawWhere != null && !rawWhere.isBlank()) {
            validateRawWhere(table, rawWhere);
            whereParts.add("(" + rawWhere.trim() + ")");
        }
        String whereClause = whereParts.isEmpty() ? null : String.join(" AND ", whereParts);
        return new Compiled(List.copyOf(directives), whereClause);
    }

    private RowSecurityDirective toDirective(UUID identityId, String table, ErasureCondition c) {
        if (c.column() == null || c.column().isBlank() || !IDENTIFIER.matcher(c.column()).matches()) {
            throw new InvalidErasureConfigException(
                    InvalidErasureConfigException.Reason.CONDITION_COLUMN_REQUIRED);
        }
        var op = c.operator();
        if (op == RowSecurityOperator.IS_NULL) {
            if (!c.values().isEmpty()) {
                throw new InvalidErasureConfigException(
                        InvalidErasureConfigException.Reason.CONDITION_VALUE_ARITY);
            }
        } else if (op.isMultiValue()) {
            if (c.values().isEmpty()) {
                throw new InvalidErasureConfigException(
                        InvalidErasureConfigException.Reason.CONDITION_VALUE_ARITY);
            }
        } else if (c.values().size() != 1) {
            throw new InvalidErasureConfigException(
                    InvalidErasureConfigException.Reason.CONDITION_VALUE_ARITY);
        }
        // Negation is applied by flipping to the complementary operator so the value stays bound.
        var effective = c.negate() ? negate(op) : op;
        return new RowSecurityDirective(identityId, table, c.column(), effective,
                List.copyOf(c.values()));
    }

    private void validateRawWhere(String table, String rawWhere) {
        try {
            sqlParserService.parse("SELECT 1 FROM " + table + " WHERE " + rawWhere);
        } catch (InvalidSqlException ex) {
            throw new InvalidErasureConfigException(
                    InvalidErasureConfigException.Reason.INVALID_RAW_WHERE);
        }
    }

    private static RowSecurityOperator negate(RowSecurityOperator op) {
        return switch (op) {
            case EQUALS -> RowSecurityOperator.NOT_EQUALS;
            case NOT_EQUALS -> RowSecurityOperator.EQUALS;
            case IN -> RowSecurityOperator.NOT_IN;
            case NOT_IN -> RowSecurityOperator.IN;
            case LESS_THAN -> RowSecurityOperator.GREATER_THAN_OR_EQUAL;
            case LESS_THAN_OR_EQUAL -> RowSecurityOperator.GREATER_THAN;
            case GREATER_THAN -> RowSecurityOperator.LESS_THAN_OR_EQUAL;
            case GREATER_THAN_OR_EQUAL -> RowSecurityOperator.LESS_THAN;
            case IS_NULL -> throw new InvalidErasureConfigException(
                    InvalidErasureConfigException.Reason.CONDITION_VALUE_ARITY);
        };
    }

    static String subjectColumn(LifecycleSubjectType type) {
        if (type == null) {
            return "id";
        }
        return switch (type) {
            case USER_ID -> "user_id";
            case EMAIL -> "email";
            case CUSTOM -> "id";
        };
    }

    /**
     * Compiled predicate inputs: bound {@link RowSecurityDirective}s the proxy conjoins onto the
     * mutation, and an optional {@code whereClause} (retention window and/or validated raw WHERE) the
     * caller inlines into the {@code DELETE} SQL text ({@code null} when none).
     */
    record Compiled(List<RowSecurityDirective> directives, String whereClause) {
        Compiled {
            directives = directives == null ? List.of() : List.copyOf(directives);
        }
    }
}
