package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.engine.databricks.DatabricksSqlTokenizer.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabricksSqlTokenizerTest {

    @Test
    void tokenizesWordsNumbersAndSymbolsWithDepth() {
        var tokens = DatabricksSqlTokenizer.tokenize("SELECT a, (b + 12) FROM t");
        assertThat(tokens).extracting(t -> t.kind()).contains(Kind.WORD, Kind.NUMBER, Kind.SYMBOL);
        var open = tokens.stream().filter(t -> t.isSymbol("(")).findFirst().orElseThrow();
        var b = tokens.stream().filter(t -> t.isWord("B")).findFirst().orElseThrow();
        assertThat(open.depth()).isZero();
        assertThat(b.depth()).isEqualTo(1);
    }

    @Test
    void uppercasesWordValueAndPreservesText() {
        var token = DatabricksSqlTokenizer.tokenize("select").get(0);
        assertThat(token.value()).isEqualTo("SELECT");
        assertThat(token.text()).isEqualTo("select");
        assertThat(token.identifier()).isEqualTo("select");
    }

    @Test
    void singleQuotedStringIsOneTokenIncludingKeywords() {
        var tokens = DatabricksSqlTokenizer.tokenize("SELECT 'FROM WHERE; --x'");
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(1).kind()).isEqualTo(Kind.STRING);
    }

    @Test
    void handlesQuoteDoublingAndBackslashEscapesInStrings() {
        assertThat(DatabricksSqlTokenizer.tokenize("SELECT 'it''s'")).hasSize(2);
        assertThat(DatabricksSqlTokenizer.tokenize("SELECT 'it\\'s'")).hasSize(2);
        assertThat(DatabricksSqlTokenizer.tokenize("SELECT \"a\\\"b\"")).hasSize(2);
    }

    @Test
    void backtickIdentifierUnquotesAndCollapsesDoubling() {
        var token = DatabricksSqlTokenizer.tokenize("`my``table`").get(0);
        assertThat(token.kind()).isEqualTo(Kind.BACKTICK_IDENT);
        assertThat(token.value()).isEqualTo("my`table");
        assertThat(token.identifier()).isEqualTo("my`table");
    }

    @Test
    void skipsLineAndBlockComments() {
        var tokens = DatabricksSqlTokenizer.tokenize("SELECT 1 -- trailing FROM x\n/* WHERE */ + 2");
        assertThat(tokens).noneMatch(t -> t.isWord("FROM"));
        assertThat(tokens).noneMatch(t -> t.isWord("WHERE"));
    }

    @Test
    void doubleColonCastIsOneSymbolNotANamedParam() {
        var tokens = DatabricksSqlTokenizer.tokenize("SELECT a::int");
        assertThat(tokens).anyMatch(t -> t.isSymbol("::"));
        assertThat(tokens).noneMatch(t -> t.kind() == Kind.NAMED_PARAM);
    }

    @Test
    void colonFollowedByIdentifierIsANamedParamToken() {
        var tokens = DatabricksSqlTokenizer.tokenize("SELECT :param_1");
        var param = tokens.get(1);
        assertThat(param.kind()).isEqualTo(Kind.NAMED_PARAM);
        assertThat(param.text()).isEqualTo(":param_1");
    }

    @Test
    void bareColonIsAPlainSymbol() {
        var tokens = DatabricksSqlTokenizer.tokenize("SELECT a : 1");
        assertThat(tokens).anyMatch(t -> t.isSymbol(":"));
        assertThat(tokens).noneMatch(t -> t.kind() == Kind.NAMED_PARAM);
    }

    @Test
    void colonInsideStringOrBacktickIsNotAMarker() {
        assertThat(DatabricksSqlTokenizer.tokenize("SELECT ':name'"))
                .noneMatch(t -> t.kind() == Kind.NAMED_PARAM);
        assertThat(DatabricksSqlTokenizer.tokenize("SELECT `col:name`"))
                .noneMatch(t -> t.kind() == Kind.NAMED_PARAM);
    }

    @Test
    void rejectsUnbalancedInput() {
        assertThatThrownBy(() -> DatabricksSqlTokenizer.tokenize("SELECT (a"))
                .isInstanceOf(DatabricksParseException.class)
                .hasMessage("error.databricks.unbalanced");
        assertThatThrownBy(() -> DatabricksSqlTokenizer.tokenize("SELECT a)"))
                .isInstanceOf(DatabricksParseException.class)
                .hasMessage("error.databricks.unbalanced");
        assertThatThrownBy(() -> DatabricksSqlTokenizer.tokenize("SELECT 'oops"))
                .isInstanceOf(DatabricksParseException.class)
                .hasMessage("error.databricks.unbalanced");
        assertThatThrownBy(() -> DatabricksSqlTokenizer.tokenize("SELECT `oops"))
                .isInstanceOf(DatabricksParseException.class)
                .hasMessage("error.databricks.unbalanced");
        assertThatThrownBy(() -> DatabricksSqlTokenizer.tokenize("SELECT /* oops"))
                .isInstanceOf(DatabricksParseException.class)
                .hasMessage("error.databricks.unbalanced");
    }

    @Test
    void recordsSourceOffsetsUsableForSplicing() {
        var sql = "SELECT * FROM t WHERE a = 1";
        var tokens = DatabricksSqlTokenizer.tokenize(sql);
        for (var token : tokens) {
            assertThat(sql.substring(token.start(), token.end())).isEqualTo(token.text());
        }
    }
}
