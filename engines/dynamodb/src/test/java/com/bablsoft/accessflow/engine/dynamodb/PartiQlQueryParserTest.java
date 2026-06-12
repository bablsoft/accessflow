package com.bablsoft.accessflow.engine.dynamodb;

import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PartiQlQueryParserTest {

    private final PartiQlQueryParser parser = new PartiQlQueryParser(TestMessages.keyEcho());

    @Test
    void classifiesSelectInsertUpdateDelete() {
        assertThat(parser.parse("SELECT * FROM \"Music\"").type()).isEqualTo(QueryType.SELECT);
        assertThat(parser.parse("INSERT INTO \"Music\" VALUE {'id': '1'}").type())
                .isEqualTo(QueryType.INSERT);
        assertThat(parser.parse("UPDATE \"Music\" SET plays = 1 WHERE \"id\" = '1'").type())
                .isEqualTo(QueryType.UPDATE);
        assertThat(parser.parse("DELETE FROM \"Music\" WHERE \"id\" = '1'").type())
                .isEqualTo(QueryType.DELETE);
    }

    @Test
    void extractsReferencedTableCasePreservedAndIndexResolvesToBase() {
        assertThat(parser.parse("SELECT * FROM \"Music\"").referencedTables()).containsExactly("Music");
        assertThat(parser.parse("SELECT * FROM \"Music\".\"GenreIndex\" WHERE g = 'rock'")
                .referencedTables()).containsExactly("Music");
        assertThat(parser.parse("UPDATE \"Orders\" SET total = 1 WHERE \"id\" = '1'")
                .referencedTables()).containsExactly("Orders");
    }

    @Test
    void detectsTopLevelWhereClause() {
        assertThat(parser.parse("SELECT * FROM \"Music\" WHERE \"id\" = '1'").hasWhereClause()).isTrue();
        assertThat(parser.parse("SELECT * FROM \"Music\"").hasWhereClause()).isFalse();
    }

    @Test
    void dispatchesJsonCommandToDdl() {
        var result = parser.parse("{\"CreateTable\": {\"TableName\": \"Music\"}}");
        assertThat(result.type()).isEqualTo(QueryType.DDL);
        assertThat(result.referencedTables()).containsExactly("Music");
        assertThat(PartiQlQueryParser.isJsonCommand("  {\"DeleteTable\": {}}")).isTrue();
        assertThat(PartiQlQueryParser.isJsonCommand("SELECT 1")).isFalse();
    }

    @Test
    void rejectsTransactionAndBatchVerbs() {
        assertThatThrownBy(() -> parser.parse("EXECUTE TRANSACTION"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("transaction_forbidden");
        assertThatThrownBy(() -> parser.parse("BEGIN"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("transaction_forbidden");
    }

    @Test
    void rejectsMultipleStatements() {
        assertThatThrownBy(() -> parser.parse("SELECT * FROM \"a\"; SELECT * FROM \"b\""))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("multiple_statements");
    }

    @Test
    void rejectsBlankAndUnsupportedStatements() {
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(InvalidSqlException.class).hasMessageContaining("blank");
        assertThatThrownBy(() -> parser.parse("DESCRIBE \"t\""))
                .isInstanceOf(InvalidSqlException.class).hasMessageContaining("unsupported_statement");
    }

    @Test
    void rejectsStatementWithoutTable() {
        assertThatThrownBy(() -> parser.parse("SELECT *"))
                .isInstanceOf(InvalidSqlException.class).hasMessageContaining("table_required");
    }

    @Test
    void toleratesTrailingSemicolon() {
        assertThat(parser.parse("SELECT * FROM \"Music\";").type()).isEqualTo(QueryType.SELECT);
    }
}
