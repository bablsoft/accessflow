package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;

import java.util.List;
import java.util.Locale;

/**
 * Statement classification for the rules (#862). {@link #isDdl} mirrors the package-prefix
 * heuristic of {@code proxy.internal.SqlParserServiceImpl#isDdl} so both sides agree on what
 * "DDL" means without a cross-module {@code internal} dependency.
 */
final class StatementKinds {

    private static final String DDL_PACKAGE_PREFIX = "net.sf.jsqlparser.statement.";

    private static final List<String> DDL_SUBPACKAGES =
            List.of("create", "alter", "drop", "truncate");

    private StatementKinds() {
    }

    static boolean isDdl(Statement statement) {
        String packageName = statement.getClass().getPackageName();
        if (!packageName.startsWith(DDL_PACKAGE_PREFIX)) {
            return false;
        }
        String tail = packageName.substring(DDL_PACKAGE_PREFIX.length());
        for (String ddlSubpackage : DDL_SUBPACKAGES) {
            if (tail.equals(ddlSubpackage) || tail.startsWith(ddlSubpackage + ".")) {
                return true;
            }
        }
        return false;
    }

    static boolean isDml(Statement statement) {
        return statement instanceof Insert || statement instanceof Update || statement instanceof Delete;
    }

    /** The JSqlParser class name as SQL words — {@code DROP}, {@code CREATE TABLE}, {@code ALTER VIEW}. */
    static String typeName(Statement statement) {
        var simple = statement.getClass().getSimpleName();
        var words = new StringBuilder(simple.length() + 4);
        for (int i = 0; i < simple.length(); i++) {
            char c = simple.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(simple.charAt(i - 1))) {
                words.append(' ');
            }
            words.append(c);
        }
        return words.toString().toUpperCase(Locale.ROOT);
    }
}
