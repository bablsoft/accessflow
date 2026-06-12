package com.bablsoft.accessflow.engine.cassandra;

/**
 * A table referenced by a CQL statement — a bare {@code table} (resolved against the datasource
 * keyspace at execution time) or a qualified {@code keyspace.table}. Identifier parts are stored in
 * their CQL <em>internal</em> form: unquoted names lowercased (CQL folds them), double-quoted names
 * case-preserved. {@link #normalized()} is the dotted form carried in
 * {@code SqlParseResult.referencedTables} and matched against permission grants and row-security
 * table refs.
 *
 * @param keyspace the keyspace qualifier, or {@code null} when the table was written unqualified
 * @param table    the table name (never {@code null})
 */
record CqlTableRef(String keyspace, String table) {

    String normalized() {
        return keyspace == null ? table : keyspace + "." + table;
    }
}
