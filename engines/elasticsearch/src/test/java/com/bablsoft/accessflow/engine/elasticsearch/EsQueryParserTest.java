package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EsQueryParserTest {

    private final EsQueryParser parser = new EsQueryParser(TestMessages.keyEcho());

    @Test
    void classifiesSearchAsSelectWithReferencedIndexAndHints() {
        var result = parser.parse("{\"search\":\"Logs-2026\",\"query\":{\"term\":{\"a\":1}},\"size\":50}");
        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.transactional()).isFalse();
        assertThat(result.referencedTables()).containsExactly("logs-2026");
        assertThat(result.hasWhereClause()).isTrue();
        assertThat(result.hasLimitClause()).isTrue();
    }

    @Test
    void searchWithoutQueryHasNoWhereClause() {
        var result = parser.parse("{\"search\":\"logs\"}");
        assertThat(result.hasWhereClause()).isFalse();
        assertThat(result.hasLimitClause()).isFalse();
    }

    @Test
    void classifiesCountAsSelect() {
        assertThat(parser.parse("{\"count\":\"logs\"}").type()).isEqualTo(QueryType.SELECT);
    }

    @Test
    void lowersGetToSearchOverIdsWithSelectType() {
        var command = parser.parseCommand("{\"get\":\"logs\",\"id\":\"abc\"}");
        assertThat(command.operation()).isEqualTo(EsOperation.SEARCH);
        assertThat(EsJson.write(command.query())).isEqualTo("{\"ids\":{\"values\":[\"abc\"]}}");
        assertThat(parser.parse("{\"get\":\"logs\",\"id\":\"abc\"}").type()).isEqualTo(QueryType.SELECT);
    }

    @Test
    void lowersMgetToSearchOverIds() {
        var command = parser.parseCommand("{\"mget\":\"logs\",\"ids\":[\"a\",\"b\"]}");
        assertThat(command.operation()).isEqualTo(EsOperation.SEARCH);
        assertThat(command.size()).isEqualTo(2);
        assertThat(EsJson.write(command.query())).isEqualTo("{\"ids\":{\"values\":[\"a\",\"b\"]}}");
    }

    @Test
    void classifiesIndexAndBulkAsInsert() {
        var index = parser.parseCommand("{\"index\":\"logs\",\"id\":\"1\",\"document\":{\"a\":1}}");
        assertThat(index.operation()).isEqualTo(EsOperation.INDEX);
        assertThat(index.docId()).isEqualTo("1");
        assertThat(parser.parse("{\"index\":\"logs\",\"document\":{\"a\":1}}").type())
                .isEqualTo(QueryType.INSERT);

        var bulk = parser.parseCommand(
                "{\"bulk\":\"logs\",\"operations\":[{\"id\":\"1\",\"document\":{\"a\":1}},{\"document\":{\"b\":2}}]}");
        assertThat(bulk.operation()).isEqualTo(EsOperation.BULK);
        assertThat(bulk.bulkItems()).hasSize(2);
        assertThat(parser.parse("{\"bulk\":\"logs\",\"operations\":[{\"document\":{\"a\":1}}]}").type())
                .isEqualTo(QueryType.INSERT);
    }

    @Test
    void classifiesByQueryAndDdl() {
        assertThat(parser.parse("{\"update_by_query\":\"logs\",\"query\":{\"match_all\":{}}}").type())
                .isEqualTo(QueryType.UPDATE);
        assertThat(parser.parse("{\"delete_by_query\":\"logs\",\"query\":{\"match_all\":{}}}").type())
                .isEqualTo(QueryType.DELETE);
        assertThat(parser.parse("{\"create_index\":\"logs\",\"mappings\":{}}").type())
                .isEqualTo(QueryType.DDL);
        assertThat(parser.parse("{\"put_mapping\":\"logs\",\"properties\":{}}").type())
                .isEqualTo(QueryType.DDL);
        assertThat(parser.parse("{\"delete_index\":\"logs\"}").type()).isEqualTo(QueryType.DDL);
    }

    @Test
    void rejectsBlankAndMalformedAndUnknownAndScripted() {
        assertThatThrownBy(() -> parser.parse("  ")).isInstanceOf(InvalidSqlException.class);
        assertThatThrownBy(() -> parser.parse("{not json")).isInstanceOf(InvalidSqlException.class);
        assertThatThrownBy(() -> parser.parse("[]")).isInstanceOf(InvalidSqlException.class);
        assertThatThrownBy(() -> parser.parse("{\"frobnicate\":\"logs\"}"))
                .isInstanceOf(InvalidSqlException.class);
        assertThatThrownBy(() -> parser.parse(
                "{\"search\":\"logs\",\"query\":{\"script\":{\"source\":\"1\"}}}"))
                .isInstanceOf(InvalidSqlException.class);
    }

    @Test
    void rejectsSystemIndexAndInvalidIndexCharacters() {
        assertThatThrownBy(() -> parser.parse("{\"search\":\"_cluster\"}"))
                .isInstanceOf(InvalidSqlException.class);
        assertThatThrownBy(() -> parser.parse("{\"search\":\".kibana\"}"))
                .isInstanceOf(InvalidSqlException.class);
        assertThatThrownBy(() -> parser.parse("{\"search\":\"a,b\"}"))
                .isInstanceOf(InvalidSqlException.class);
        assertThatThrownBy(() -> parser.parse("{\"search\":\"a b\"}"))
                .isInstanceOf(InvalidSqlException.class);
    }

    @Test
    void rejectsBulkOperationWithoutADocument() {
        assertThatThrownBy(() -> parser.parse(
                "{\"bulk\":\"logs\",\"operations\":[{\"delete\":{\"_id\":\"1\"}}]}"))
                .isInstanceOf(InvalidSqlException.class);
    }

    @Test
    void rejectsIndexWithoutADocument() {
        assertThatThrownBy(() -> parser.parse("{\"index\":\"logs\"}"))
                .isInstanceOf(InvalidSqlException.class);
    }
}
