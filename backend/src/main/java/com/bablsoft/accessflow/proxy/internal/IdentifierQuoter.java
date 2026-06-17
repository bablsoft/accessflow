package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DbType;

/**
 * Dialect-aware SQL identifier quoting for the relational sample-data path (issue AF-443). The
 * schema/table names always originate from introspection metadata (an allow-list of the
 * datasource's own catalog) — never from raw request input — but they are still quoted and escaped
 * here so reserved words, mixed case, and special characters survive, and so the generated
 * {@code SELECT * FROM …} can never be coerced into something other than an identifier reference.
 */
final class IdentifierQuoter {

    private IdentifierQuoter() {
    }

    /**
     * Builds a dialect-quoted, optionally schema-qualified table reference (e.g. {@code "public"."users"}
     * or {@code `db`.`orders`}). A blank schema yields just the quoted table name.
     */
    static String qualifiedTable(DbType dbType, String schema, String table) {
        var quotedTable = quote(dbType, table);
        if (schema == null || schema.isBlank()) {
            return quotedTable;
        }
        return quote(dbType, schema) + "." + quotedTable;
    }

    static String quote(DbType dbType, String identifier) {
        return switch (dbType) {
            case MYSQL, MARIADB -> "`" + identifier.replace("`", "``") + "`";
            case MSSQL -> "[" + identifier.replace("]", "]]") + "]";
            // POSTGRESQL, ORACLE, CUSTOM and any other dialect: ANSI double-quote.
            default -> "\"" + identifier.replace("\"", "\"\"") + "\"";
        };
    }
}
