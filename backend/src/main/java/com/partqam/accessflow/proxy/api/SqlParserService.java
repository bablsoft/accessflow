package com.partqam.accessflow.proxy.api;

/**
 * Parses user-submitted SQL with JSqlParser and classifies it by {@link
 * com.partqam.accessflow.core.api.QueryType}. This is the first defence layer of the proxy
 * engine: queries that fail to parse — including stacked / multi-statement input — are rejected
 * with {@link InvalidSqlException}, which the global handler maps to HTTP 422.
 */
public interface SqlParserService {

    /**
     * Parse {@code sql} and return its {@link SqlParseResult}.
     *
     * @throws InvalidSqlException if {@code sql} is null, blank, contains multiple statements, or
     *         is otherwise unparseable.
     */
    SqlParseResult parse(String sql);
}
