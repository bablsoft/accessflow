package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryEngine;
import com.bablsoft.accessflow.core.api.QueryEngineCatalog;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultQueryParserTest {

    private final SqlParserService sqlParserService = mock(SqlParserService.class);
    private final QueryEngineCatalog engineCatalog = mock(QueryEngineCatalog.class);
    private final QueryEngine engine = mock(QueryEngine.class);
    private final DefaultQueryParser parser = new DefaultQueryParser(sqlParserService, engineCatalog);

    @Test
    void routesRelationalToSqlParser() {
        when(sqlParserService.parse("SELECT 1"))
                .thenReturn(new SqlParseResult(QueryType.SELECT, "SELECT 1"));
        var result = parser.parse("SELECT 1", DbType.POSTGRESQL);
        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        verify(sqlParserService).parse("SELECT 1");
        verify(engineCatalog, never()).engineFor(any());
    }

    @Test
    void routesMongoToEngineFromCatalog() {
        var mongoResult = new SqlParseResult(QueryType.SELECT, false,
                List.of("db.users.find({})"), Set.of("users"), false, false);
        when(engineCatalog.engineFor(DbType.MONGODB)).thenReturn(engine);
        when(engine.parse("db.users.find({})")).thenReturn(mongoResult);

        var result = parser.parse("db.users.find({})", DbType.MONGODB);

        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.referencedTables()).containsExactly("users");
        verify(sqlParserService, never()).parse(anyString());
    }
}
