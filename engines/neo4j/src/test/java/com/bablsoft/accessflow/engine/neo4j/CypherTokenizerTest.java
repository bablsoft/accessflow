package com.bablsoft.accessflow.engine.neo4j;

import com.bablsoft.accessflow.engine.neo4j.CypherTokenizer.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CypherTokenizerTest {

    @Test
    void tracksDepthAndEnclosingBracket() {
        var tokens = CypherTokenizer.tokenize("MATCH (n:User {name: 'x'}) RETURN n");
        var open = tokens.stream().filter(t -> t.isSymbol("{")).findFirst().orElseThrow();
        // The map's contents are enclosed by '{'; the node label colon by '('.
        var nameToken = tokens.stream().filter(t -> t.kind() == Kind.WORD && t.value().equals("NAME"))
                .findFirst().orElseThrow();
        assertThat(open.enclosing()).isEqualTo('(');
        assertThat(nameToken.enclosing()).isEqualTo('{');
    }

    @Test
    void uppercasesKeywordsButPreservesIdentifierCase() {
        var tokens = CypherTokenizer.tokenize("match (n:User) return n");
        assertThat(tokens.get(0).value()).isEqualTo("MATCH");
        var label = tokens.stream().filter(t -> t.text().equals("User")).findFirst().orElseThrow();
        assertThat(label.identifier()).isEqualTo("User");
    }

    @Test
    void treatsStringsAndBacktickIdentifiersAsSingleTokens() {
        var tokens = CypherTokenizer.tokenize("RETURN 'a:b', `weird label`");
        assertThat(tokens).anyMatch(t -> t.kind() == Kind.STRING && t.text().equals("'a:b'"));
        var quoted = tokens.stream().filter(t -> t.kind() == Kind.QUOTED_IDENT).findFirst().orElseThrow();
        assertThat(quoted.identifier()).isEqualTo("weird label");
    }

    @Test
    void handlesBackslashEscapesInStrings() {
        var tokens = CypherTokenizer.tokenize("RETURN 'it\\'s ok'");
        assertThat(tokens).anyMatch(t -> t.kind() == Kind.STRING && t.text().equals("'it\\'s ok'"));
    }

    @Test
    void skipsLineAndBlockCommentsButNotDoubleDash() {
        var tokens = CypherTokenizer.tokenize("MATCH (a) // c\n/* b */ -->(b) RETURN a");
        // `-->` is relationship syntax, never a comment: the dashes survive as symbols.
        assertThat(tokens).anyMatch(t -> t.isSymbol("-"));
        assertThat(tokens).noneMatch(t -> t.kind() == Kind.WORD && t.value().equals("C"));
    }

    @Test
    void rejectsUnbalancedBrackets() {
        assertThatThrownBy(() -> CypherTokenizer.tokenize("MATCH (n:User RETURN n"))
                .isInstanceOf(CypherParseException.class)
                .extracting(e -> ((CypherParseException) e).messageKey())
                .isEqualTo("error.neo4j.unbalanced");
    }

    @Test
    void rejectsMismatchedBrackets() {
        assertThatThrownBy(() -> CypherTokenizer.tokenize("RETURN [1, 2)"))
                .isInstanceOf(CypherParseException.class);
    }

    @Test
    void rejectsUnterminatedString() {
        assertThatThrownBy(() -> CypherTokenizer.tokenize("RETURN 'oops"))
                .isInstanceOf(CypherParseException.class);
    }
}
