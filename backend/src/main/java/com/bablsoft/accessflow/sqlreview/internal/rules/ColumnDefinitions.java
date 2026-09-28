package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Column-definition helpers for the structured-DDL rules (#1079). JSqlParser 5.4 keeps a column's
 * constraints as a token list ({@code [NOT, NULL, DEFAULT, 0]}), so nullability, defaults and
 * generated-value markers are read from those tokens.
 */
final class ColumnDefinitions {

    private static final Set<String> SERIAL_TYPES = Set.of(
            "SERIAL", "BIGSERIAL", "SMALLSERIAL", "SERIAL2", "SERIAL4", "SERIAL8");
    private static final Set<String> GENERATED_TOKENS = Set.of("IDENTITY", "AUTO_INCREMENT", "AUTOINCREMENT");

    private ColumnDefinitions() {
    }

    /** The column definitions of every {@code ALTER TABLE} action with one of {@code operations}. */
    static List<Target> of(Alter alter, Set<AlterOperation> operations) {
        var out = new ArrayList<Target>();
        if (alter.getAlterExpressions() == null) {
            return out;
        }
        for (AlterExpression expression : alter.getAlterExpressions()) {
            if (!operations.contains(expression.getOperation()) || expression.getColDataTypeList() == null) {
                continue;
            }
            for (AlterExpression.ColumnDataType column : expression.getColDataTypeList()) {
                out.add(new Target(expression, column));
            }
        }
        return out;
    }

    /** {@code table.column}, normalised the way every rule reports names. */
    static String qualified(Alter alter, String column) {
        return TableNames.normalize(alter.getTable()) + "." + TableNames.normalize(column);
    }

    static boolean isNotNull(ColumnDefinition column) {
        var specs = upperSpecs(column);
        for (int i = 0; i + 1 < specs.size(); i++) {
            if ("NOT".equals(specs.get(i)) && "NULL".equals(specs.get(i + 1))) {
                return true;
            }
        }
        return false;
    }

    static boolean hasDefault(ColumnDefinition column) {
        return upperSpecs(column).contains("DEFAULT");
    }

    /** Identity, auto-increment and serial columns fill themselves, so NOT NULL is safe to add. */
    static boolean isGenerated(ColumnDefinition column) {
        var type = column.getColDataType();
        if (type != null && type.getDataType() != null
                && SERIAL_TYPES.contains(type.getDataType().toUpperCase(Locale.ROOT))) {
            return true;
        }
        // Whole tokens only: a REFERENCES target, CHECK expression or COMMENT text may contain the
        // same words. JSqlParser keeps GENERATED … AS IDENTITY as one token starting with GENERATED.
        for (String spec : upperSpecs(column)) {
            if (GENERATED_TOKENS.contains(spec) || spec.startsWith("GENERATED ")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> upperSpecs(ColumnDefinition column) {
        var specs = column.getColumnSpecs();
        if (specs == null) {
            return List.of();
        }
        var out = new ArrayList<String>(specs.size());
        for (String spec : specs) {
            if (spec != null) {
                out.add(spec.trim().toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** One column definition and the {@code ALTER TABLE} action that carries it. */
    record Target(AlterExpression expression, AlterExpression.ColumnDataType column) {
    }
}
