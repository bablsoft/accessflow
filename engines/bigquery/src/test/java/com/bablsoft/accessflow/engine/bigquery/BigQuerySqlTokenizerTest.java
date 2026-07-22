package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.engine.bigquery.BigQuerySqlTokenizer.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BigQuerySqlTokenizerTest {

    @Test
    void tokenizesWordsNumbersAndSymbolsWithDepth() {
        var tokens = BigQuerySqlTokenizer.tokenize("SELECT a, (1 + 2) FROM t");
        assertThat(tokens).extracting("value")
                .containsExactly("SELECT", "A", ",", "(", "1", "+", "2", ")", "FROM", "T");
        assertThat(tokens.get(4).depth()).isEqualTo(1); // "1" inside the parens
        assertThat(tokens.get(8).depth()).isZero();     // FROM back at top level
    }

    @Test
    void uppercasesWordValuesButKeepsText() {
        var token = BigQuerySqlTokenizer.tokenize("select").get(0);
        assertThat(token.value()).isEqualTo("SELECT");
        assertThat(token.text()).isEqualTo("select");
    }

    @Test
    void skipsLineAndBlockComments() {
        assertThat(BigQuerySqlTokenizer.tokenize("SELECT 1 -- WHERE hidden\n# SET too\n/* JOIN */ FROM t"))
                .extracting("value").containsExactly("SELECT", "1", "FROM", "T");
    }

    @Test
    void singleAndDoubleQuotedStringsAreSingleTokens() {
        var tokens = BigQuerySqlTokenizer.tokenize("SELECT 'a; WHERE', \"b -- c\"");
        assertThat(tokens).filteredOn(t -> t.kind() == Kind.STRING).hasSize(2);
        assertThat(tokens).extracting("value").doesNotContain("WHERE");
    }

    @Test
    void backslashEscapesInsideStrings() {
        var tokens = BigQuerySqlTokenizer.tokenize("SELECT 'it\\'s fine'");
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(1).kind()).isEqualTo(Kind.STRING);
        assertThat(tokens.get(1).text()).isEqualTo("'it\\'s fine'");
    }

    @Test
    void tripleQuotedStringsSpanQuotesAndNewlines() {
        var tokens = BigQuerySqlTokenizer.tokenize("SELECT '''a 'b' c\nd''' FROM t");
        assertThat(tokens).extracting("value").containsExactly("SELECT", "'''a 'b' c\nd'''", "FROM", "T");
    }

    @Test
    void rawAndBytesPrefixedStringsAreStrings() {
        var tokens = BigQuerySqlTokenizer.tokenize("SELECT r'a\\', b\"x\", rb'''y'''");
        assertThat(tokens).filteredOn(t -> t.kind() == Kind.STRING)
                .extracting("text").containsExactly("r'a\\'", "b\"x\"", "rb'''y'''");
    }

    @Test
    void rawPrefixDisablesBackslashEscapes() {
        // In a raw string the backslash does NOT escape the closing quote.
        var tokens = BigQuerySqlTokenizer.tokenize("SELECT r'a\\' , x");
        assertThat(tokens).extracting("value").contains(",", "X");
    }

    @Test
    void backtickIdentifiersAreQuotedIdentsWithDotsPreserved() {
        var tokens = BigQuerySqlTokenizer.tokenize("SELECT * FROM `my-project.data set.T1`");
        var ident = tokens.get(tokens.size() - 1);
        assertThat(ident.kind()).isEqualTo(Kind.QUOTED_IDENT);
        assertThat(ident.value()).isEqualTo("my-project.data set.T1");
        assertThat(ident.identifier()).isEqualTo("my-project.data set.T1");
    }

    @Test
    void escapedBacktickInsideBacktickIdentifier() {
        var tokens = BigQuerySqlTokenizer.tokenize("SELECT * FROM `we\\`ird`");
        assertThat(tokens.get(3).value()).isEqualTo("we`ird");
    }

    @Test
    void wordsThatLookLikePrefixesButAreNotStringsStayWords() {
        var tokens = BigQuerySqlTokenizer.tokenize("SELECT r, b FROM t");
        assertThat(tokens).extracting("kind")
                .containsExactly(Kind.WORD, Kind.WORD, Kind.SYMBOL, Kind.WORD, Kind.WORD, Kind.WORD);
    }

    @Test
    void bracketsTrackDepthLikeParens() {
        var tokens = BigQuerySqlTokenizer.tokenize("SELECT [1, 2] FROM t");
        assertThat(tokens.get(2).depth()).isEqualTo(1); // "1" inside the array literal
    }

    @Test
    void unbalancedInputThrows() {
        assertThatThrownBy(() -> BigQuerySqlTokenizer.tokenize("SELECT (1"))
                .isInstanceOf(BigQueryParseException.class).hasMessage("error.bigquery.unbalanced");
        assertThatThrownBy(() -> BigQuerySqlTokenizer.tokenize("SELECT 1)"))
                .isInstanceOf(BigQueryParseException.class).hasMessage("error.bigquery.unbalanced");
        assertThatThrownBy(() -> BigQuerySqlTokenizer.tokenize("SELECT 'oops"))
                .isInstanceOf(BigQueryParseException.class).hasMessage("error.bigquery.unbalanced");
        assertThatThrownBy(() -> BigQuerySqlTokenizer.tokenize("SELECT '''oops"))
                .isInstanceOf(BigQueryParseException.class).hasMessage("error.bigquery.unbalanced");
        assertThatThrownBy(() -> BigQuerySqlTokenizer.tokenize("SELECT `oops"))
                .isInstanceOf(BigQueryParseException.class).hasMessage("error.bigquery.unbalanced");
        assertThatThrownBy(() -> BigQuerySqlTokenizer.tokenize("SELECT /* oops"))
                .isInstanceOf(BigQueryParseException.class).hasMessage("error.bigquery.unbalanced");
    }

    @Test
    void questionMarkAndAtSignAreSymbols() {
        var tokens = BigQuerySqlTokenizer.tokenize("WHERE a = ? AND b = @p");
        assertThat(tokens).filteredOn(t -> t.isSymbol("?")).hasSize(1);
        assertThat(tokens).filteredOn(t -> t.isSymbol("@")).hasSize(1);
    }
}
