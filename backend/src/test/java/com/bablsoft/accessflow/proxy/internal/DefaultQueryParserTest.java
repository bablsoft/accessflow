package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.proxy.api.SqlParseResult;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import com.bablsoft.accessflow.proxy.internal.mongo.MongoQueryParser;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultQueryParserTest {

    private final SqlParserService sqlParserService = mock(SqlParserService.class);
    private final MongoQueryParser mongoQueryParser = new MongoQueryParser(messageSource());
    private final DefaultQueryParser parser = new DefaultQueryParser(sqlParserService, mongoQueryParser);

    private static StaticMessageSource messageSource() {
        var source = new StaticMessageSource();
        source.setUseCodeAsDefaultMessage(true);
        return source;
    }

    @Test
    void routesRelationalToSqlParser() {
        when(sqlParserService.parse("SELECT 1"))
                .thenReturn(new SqlParseResult(QueryType.SELECT, "SELECT 1"));
        var result = parser.parse("SELECT 1", DbType.POSTGRESQL);
        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        verify(sqlParserService).parse("SELECT 1");
    }

    @Test
    void routesMongoToMongoParser() {
        var result = parser.parse("db.users.find({})", DbType.MONGODB);
        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.referencedTables()).containsExactly("users");
        verify(sqlParserService, never()).parse(anyString());
    }
}
