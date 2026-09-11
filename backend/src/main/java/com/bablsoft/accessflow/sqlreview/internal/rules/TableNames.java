package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Table-name helpers for the rules (#862). {@link #normalize} mirrors
 * {@code proxy.internal.SqlParserServiceImpl#normalizeIdentifier} — quotes and brackets stripped,
 * lower-cased — the same way {@code access.internal.GrantTargetNormalizer} and
 * {@code compliance.internal.TableNameNormalizer} do, because a module may not reach into another
 * module's {@code internal} package and the helper is intentionally tiny.
 */
final class TableNames {

    private TableNames() {
    }

    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        var stripped = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"' || c == '`' || c == '[' || c == ']') {
                continue;
            }
            stripped.append(c);
        }
        return stripped.toString().toLowerCase(Locale.ROOT);
    }

    /** The normalised {@code schema.table} / {@code table} name of an AST table reference. */
    static String normalize(Table table) {
        return table == null ? "" : normalize(table.getFullyQualifiedName());
    }

    /** The text after the last dot — {@code payroll.salaries} → {@code salaries}. */
    static String bareName(String normalized) {
        int dot = normalized.lastIndexOf('.');
        return dot < 0 ? normalized : normalized.substring(dot + 1);
    }

    /**
     * Every table the statement references, normalised and sorted; CTE aliases are excluded by
     * {@link TablesNamesFinder}. Empty when JSqlParser cannot walk the statement shape — the
     * same guard the proxy parser applies.
     */
    static Set<String> referencedTables(Statement statement) {
        Set<String> raw;
        try {
            raw = new TablesNamesFinder<>().getTables(statement);
        } catch (RuntimeException ex) {
            return Set.of();
        }
        var out = new TreeSet<String>();
        if (raw != null) {
            for (String name : raw) {
                out.add(normalize(name));
            }
        }
        return out;
    }
}
