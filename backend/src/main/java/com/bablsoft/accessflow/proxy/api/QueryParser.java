package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.SqlParseResult;

/**
 * Engine-aware query validator. Dispatches a submitted query string to the parser for the
 * datasource's {@link DbType}: relational dialects are parsed with JSqlParser (the existing
 * {@link SqlParserService}); engine-managed types (e.g. {@link DbType#MONGODB},
 * {@link DbType#COUCHBASE}) are parsed by their engine plugin. Both
 * return the engine-neutral {@link SqlParseResult} so the workflow layer treats every engine
 * uniformly (query type for the permission model, {@code referencedTables} as the allow-list keys —
 * collection/keyspace names for the NoSQL engines — and {@code transactional}/{@code statements}
 * for execution).
 *
 * @throws InvalidSqlException if the query is null, blank, multi-statement, references an
 *         unsupported operation, or is otherwise unparseable for the engine.
 */
public interface QueryParser {

    SqlParseResult parse(String query, DbType dbType);
}
