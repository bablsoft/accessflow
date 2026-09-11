package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import net.sf.jsqlparser.statement.select.Select;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SqlStatementParserTest {

    @Test
    void singleStatementIsVerbatimWithKnownLines() {
        var contexts = SqlStatementParser.parse(new SqlParseResult(QueryType.SELECT, "SELECT a\nFROM t"));
        assertThat(contexts).singleElement().satisfies(c -> {
            assertThat(c.statementIndex()).isZero();
            assertThat(c.transactional()).isFalse();
            assertThat(c.lineNumbersKnown()).isTrue();
            assertThat(c.statement()).isInstanceOf(Select.class);
        });
    }

    @Test
    void envelopeSlicesAreIndexedTransactionalAndLineless() {
        var contexts = SqlStatementParser.parse(new SqlParseResult(QueryType.INSERT, true,
                List.of("INSERT INTO t VALUES (1)", "UPDATE t SET a = 2"), Set.of("t")));
        assertThat(contexts).hasSize(2);
        assertThat(contexts.get(1).statementIndex()).isEqualTo(1);
        assertThat(contexts).allSatisfy(c -> {
            assertThat(c.transactional()).isTrue();
            assertThat(c.lineNumbersKnown()).isFalse();
        });
    }

    @Test
    void unparseableSliceIsSkippedKeepingTheOthersIndices() {
        var contexts = SqlStatementParser.parse(new SqlParseResult(QueryType.OTHER, true,
                List.of("DELETE FROM t", "THIS IS NOT SQL", "DELETE FROM u"), Set.of()));
        assertThat(contexts).extracting(c -> c.statementIndex()).containsExactly(0, 2);
    }
}
