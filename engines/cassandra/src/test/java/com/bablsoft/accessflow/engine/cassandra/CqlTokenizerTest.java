package com.bablsoft.accessflow.engine.cassandra;

import com.bablsoft.accessflow.engine.cassandra.CqlTokenizer.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CqlTokenizerTest {

    @Test
    void tokenizesKeywordsUppercaseAndTracksDepth() {
        var tokens = CqlTokenizer.tokenize("SELECT * FROM users WHERE id IN (1, 2)");
        assertThat(tokens.get(0).kind()).isEqualTo(Kind.WORD);
        assertThat(tokens.get(0).value()).isEqualTo("SELECT");
        var open = tokens.stream().filter(t -> t.isSymbol("(")).findFirst().orElseThrow();
        var innerNumber = tokens.stream()
                .filter(t -> t.kind() == Kind.NUMBER && t.value().equals("1")).findFirst().orElseThrow();
        assertThat(open.depth()).isZero();
        assertThat(innerNumber.depth()).isEqualTo(1);
    }

    @Test
    void singleQuotedStringIsOneTokenAndShieldsKeywords() {
        var tokens = CqlTokenizer.tokenize("SELECT * FROM t WHERE s = 'FROM where'");
        var string = tokens.stream().filter(t -> t.kind() == Kind.STRING).findFirst().orElseThrow();
        assertThat(string.text()).isEqualTo("'FROM where'");
        assertThat(tokens.stream().filter(t -> t.isWord("WHERE")).count()).isEqualTo(1);
    }

    @Test
    void doubleQuotedIdentifierPreservesCase() {
        var tokens = CqlTokenizer.tokenize("SELECT \"MixedCase\" FROM t");
        var ident = tokens.stream().filter(t -> t.kind() == Kind.QUOTED_IDENT).findFirst().orElseThrow();
        assertThat(ident.value()).isEqualTo("MixedCase");
        assertThat(ident.identifier()).isEqualTo("MixedCase");
    }

    @Test
    void unquotedWordIdentifierFoldsToLowercase() {
        var tokens = CqlTokenizer.tokenize("SELECT Name FROM t");
        var name = tokens.get(1);
        assertThat(name.identifier()).isEqualTo("name");
    }

    @Test
    void skipsLineAndBlockComments() {
        var tokens = CqlTokenizer.tokenize("SELECT id -- a\n/* b */ FROM t // c");
        assertThat(tokens).noneMatch(t -> t.text().contains("a") || t.text().contains("b"));
        assertThat(tokens).anyMatch(t -> t.isWord("FROM"));
    }

    @Test
    void rejectsUnbalancedParensAndQuotes() {
        assertThatThrownBy(() -> CqlTokenizer.tokenize("SELECT * FROM t WHERE x IN (1, 2"))
                .isInstanceOf(CqlParseException.class)
                .hasMessage("error.cassandra.unbalanced");
        assertThatThrownBy(() -> CqlTokenizer.tokenize("SELECT 'oops FROM t"))
                .isInstanceOf(CqlParseException.class)
                .hasMessage("error.cassandra.unbalanced");
    }
}
