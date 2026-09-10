package com.bablsoft.accessflow.core.api;

import java.util.Locale;

/**
 * A masking policy's {@code column_ref} parsed at three levels of specificity, so a resolver can
 * pick the most specific policy that covers a result column.
 *
 * <p>Extracted from {@code ColumnMaskResolver} so the policy simulator (issue AF-630) can match the
 * same way. The simulator only ever has bare column names — persisted result columns record a name
 * and a JDBC type but no schema or table — so it calls {@link #matchLevel} with nulls and gets
 * exactly bare-name matching, which is honest rather than guessed.
 *
 * <p>It lives beside {@link ResolvedColumnMask} because that is the type whose {@code columnRef} it
 * parses, and because the access explainer (issue AF-859) needs the same parse from another module
 * to say which masking policies touch a referenced table. A second parser would be free to disagree
 * with the one that masks real result columns.
 */
public record ColumnRefKeys(String full, String table, String bare) {

    public static ColumnRefKeys parse(String entry) {
        var lower = entry.trim().toLowerCase(Locale.ROOT);
        var parts = lower.split("\\.");
        return switch (parts.length) {
            case 1 -> new ColumnRefKeys(null, null, parts[0]);
            case 2 -> new ColumnRefKeys(null, parts[0] + "." + parts[1], parts[1]);
            default -> new ColumnRefKeys(
                    parts[parts.length - 3] + "." + parts[parts.length - 2] + "."
                            + parts[parts.length - 1],
                    parts[parts.length - 2] + "." + parts[parts.length - 1],
                    parts[parts.length - 1]);
        };
    }

    /** 3 = schema.table.column, 2 = table.column, 1 = bare column, 0 = no match. */
    public int matchLevel(String schema, String table, String column) {
        if (full != null && schema != null && table != null
                && full.equals(schema + "." + table + "." + column)) {
            return 3;
        }
        if (this.table != null && table != null && this.table.equals(table + "." + column)) {
            return 2;
        }
        if (bare.equals(column)) {
            return 1;
        }
        return 0;
    }
}
