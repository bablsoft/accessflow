package com.bablsoft.accessflow.engine.snowflake;

import com.bablsoft.accessflow.engine.snowflake.SnowflakeSqlTokenizer.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakeSqlTokenizerTest {

    @Test
    void tokenizesWordsNumbersAndSymbolsWithUppercasedWordValues() {
        var tokens = SnowflakeSqlTokenizer.tokenize("select a1, _b$2 from t");
        assertThat(tokens).extracting(SnowflakeSqlTokenizer.Token::value)
                .containsExactly("SELECT", "A1", ",", "_B$2", "FROM", "T");
        assertThat(tokens.get(0).kind()).isEqualTo(Kind.WORD);
        assertThat(tokens.get(0).text()).isEqualTo("select");
    }

    @Test
    void singleQuotedStringWithDoublingIsOneToken() {
        var tokens = SnowflakeSqlTokenizer.tokenize("SELECT 'it''s from x'");
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(1).kind()).isEqualTo(Kind.STRING);
        assertThat(tokens.get(1).text()).isEqualTo("'it''s from x'");
    }

    @Test
    void backslashEscapedQuoteStaysInsideTheString() {
        var tokens = SnowflakeSqlTokenizer.tokenize("SELECT 'it\\'s from x'");
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(1).kind()).isEqualTo(Kind.STRING);
        assertThat(tokens.get(1).text()).isEqualTo("'it\\'s from x'");
    }

    @Test
    void quotedIdentifierKeepsCaseAndUnescapesDoubledQuotes() {
        var tokens = SnowflakeSqlTokenizer.tokenize("SELECT \"My \"\"Col\"\"\" FROM t");
        assertThat(tokens.get(1).kind()).isEqualTo(Kind.QUOTED_IDENT);
        assertThat(tokens.get(1).value()).isEqualTo("My \"Col\"");
        assertThat(tokens.get(1).identifier()).isEqualTo("My \"Col\"");
    }

    @Test
    void dollarQuotedBlockIsOneOpaqueStringToken() {
        var tokens = SnowflakeSqlTokenizer.tokenize("SELECT $$ semi ; 'quote' FROM t $$");
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(1).kind()).isEqualTo(Kind.STRING);
        assertThat(tokens.get(1).text()).startsWith("$$").endsWith("$$");
    }

    @Test
    void commentsAreSkipped() {
        var tokens = SnowflakeSqlTokenizer.tokenize(
                "SELECT 1 -- line\n + 2 // slash\n /* block ; */ + 3");
        assertThat(tokens).extracting(SnowflakeSqlTokenizer.Token::text)
                .containsExactly("SELECT", "1", "+", "2", "+", "3");
    }

    @Test
    void tracksParenDepth() {
        var tokens = SnowflakeSqlTokenizer.tokenize("f(a, g(b))");
        assertThat(tokens.get(0).depth()).isZero();          // f
        assertThat(tokens.get(1).depth()).isZero();          // ( records pre-increment depth
        assertThat(tokens.get(2).depth()).isEqualTo(1);      // a
        assertThat(tokens.get(6).depth()).isEqualTo(2);      // b
        assertThat(tokens.get(tokens.size() - 1).depth()).isZero(); // final )
    }

    @Test
    void questionMarkIsASingleSymbolToken() {
        var tokens = SnowflakeSqlTokenizer.tokenize("a = ?");
        assertThat(tokens.get(2).isSymbol("?")).isTrue();
    }

    @Test
    void unbalancedInputIsRejected() {
        assertThatThrownBy(() -> SnowflakeSqlTokenizer.tokenize("SELECT (1"))
                .isInstanceOf(SnowflakeParseException.class)
                .hasMessage("error.snowflake.unbalanced");
        assertThatThrownBy(() -> SnowflakeSqlTokenizer.tokenize("SELECT 1)"))
                .isInstanceOf(SnowflakeParseException.class)
                .hasMessage("error.snowflake.unbalanced");
        assertThatThrownBy(() -> SnowflakeSqlTokenizer.tokenize("SELECT 'open"))
                .isInstanceOf(SnowflakeParseException.class)
                .hasMessage("error.snowflake.unbalanced");
        assertThatThrownBy(() -> SnowflakeSqlTokenizer.tokenize("SELECT \"open"))
                .isInstanceOf(SnowflakeParseException.class)
                .hasMessage("error.snowflake.unbalanced");
        assertThatThrownBy(() -> SnowflakeSqlTokenizer.tokenize("SELECT /* open"))
                .isInstanceOf(SnowflakeParseException.class)
                .hasMessage("error.snowflake.unbalanced");
        assertThatThrownBy(() -> SnowflakeSqlTokenizer.tokenize("SELECT $$ open"))
                .isInstanceOf(SnowflakeParseException.class)
                .hasMessage("error.snowflake.unbalanced");
    }

    @Test
    void tokenOffsetsIndexTheOriginalText() {
        var sql = "SELECT x FROM t";
        var tokens = SnowflakeSqlTokenizer.tokenize(sql);
        for (var token : tokens) {
            assertThat(sql.substring(token.start(), token.end())).isEqualTo(token.text());
        }
    }
}
