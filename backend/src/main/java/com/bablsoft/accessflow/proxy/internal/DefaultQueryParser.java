package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryEngineCatalog;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Engine-aware {@link QueryParser} that routes a submitted query to the parser for its
 * {@link DbType}: {@link DbType#MONGODB} to the MongoDB engine plugin resolved from the
 * {@link QueryEngineCatalog}, every relational dialect to the existing JSqlParser-backed
 * {@link SqlParserService}.
 */
@Service
@RequiredArgsConstructor
class DefaultQueryParser implements QueryParser {

    private final SqlParserService sqlParserService;
    private final QueryEngineCatalog engineCatalog;

    @Override
    public SqlParseResult parse(String query, DbType dbType) {
        return dbType == DbType.MONGODB
                ? engineCatalog.engineFor(dbType).parse(query)
                : sqlParserService.parse(query);
    }
}
