package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import com.bablsoft.accessflow.proxy.internal.mongo.MongoQueryParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Engine-aware {@link QueryParser} that routes a submitted query to the parser for its
 * {@link DbType}: {@link DbType#MONGODB} to {@link MongoQueryParser}, every relational dialect to the
 * existing JSqlParser-backed {@link SqlParserService}.
 */
@Service
@RequiredArgsConstructor
class DefaultQueryParser implements QueryParser {

    private final SqlParserService sqlParserService;
    private final MongoQueryParser mongoQueryParser;

    @Override
    public SqlParseResult parse(String query, DbType dbType) {
        return dbType == DbType.MONGODB
                ? mongoQueryParser.parse(query)
                : sqlParserService.parse(query);
    }
}
