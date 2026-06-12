package com.bablsoft.accessflow.engine.dynamodb;

import com.bablsoft.accessflow.engine.dynamodb.PartiQlTokenizer.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PartiQlTokenizerTest {

    @Test
    void tokenizesWordsQuotedIdentsStringsNumbersAndSymbols() {
        var tokens = PartiQlTokenizer.tokenize("SELECT * FROM \"Music\" WHERE \"id\" = 'x' AND n = 5");
        assertThat(tokens.get(0).kind()).isEqualTo(Kind.WORD);
        assertThat(tokens.get(0).value()).isEqualTo("SELECT");
        assertThat(tokens).anyMatch(t -> t.kind() == Kind.QUOTED_IDENT && t.value().equals("Music"));
        assertThat(tokens).anyMatch(t -> t.kind() == Kind.STRING && t.text().equals("'x'"));
        assertThat(tokens).anyMatch(t -> t.kind() == Kind.NUMBER && t.text().equals("5"));
    }

    @Test
    void preservesIdentifierCaseForDynamoDb() {
        var tokens = PartiQlTokenizer.tokenize("SELECT * FROM \"MixedCase\"");
        assertThat(tokens).anyMatch(t -> t.kind() == Kind.QUOTED_IDENT && t.identifier().equals("MixedCase"));
        // Unquoted WORD keeps its source case via identifier()/text().
        var unquoted = PartiQlTokenizer.tokenize("SELECT * FROM Orders");
        assertThat(unquoted).anyMatch(t -> t.kind() == Kind.WORD && t.identifier().equals("Orders"));
    }

    @Test
    void tracksBracketAndBraceDepthForInsertValueLiterals() {
        var tokens = PartiQlTokenizer.tokenize("INSERT INTO \"t\" VALUE {'a': [1, 2], 'b': 3}");
        // The map/list literal contents sit at depth > 0.
        assertThat(tokens).anyMatch(t -> t.depth() > 0 && t.text().equals("1"));
        assertThat(tokens).anyMatch(t -> t.depth() == 0 && t.isWord("VALUE"));
    }

    @Test
    void skipsLineAndBlockComments() {
        var tokens = PartiQlTokenizer.tokenize("SELECT * -- comment\nFROM \"t\" /* block */ WHERE id = 1");
        assertThat(tokens).noneMatch(t -> t.text().contains("comment"));
        assertThat(tokens).anyMatch(t -> t.isWord("WHERE"));
    }

    @Test
    void doublesQuotesInsideLiteralsAreOneToken() {
        var tokens = PartiQlTokenizer.tokenize("SELECT * FROM \"a\"\"b\" WHERE x = 'O''Brien'");
        assertThat(tokens).anyMatch(t -> t.kind() == Kind.QUOTED_IDENT && t.value().equals("a\"b"));
        assertThat(tokens).anyMatch(t -> t.kind() == Kind.STRING && t.text().equals("'O''Brien'"));
    }

    @Test
    void rejectsUnterminatedStringAndUnbalancedBrackets() {
        assertThatThrownBy(() -> PartiQlTokenizer.tokenize("SELECT * FROM \"t\" WHERE x = 'open"))
                .isInstanceOf(PartiQlParseException.class)
                .hasMessage("error.dynamodb.unbalanced");
        assertThatThrownBy(() -> PartiQlTokenizer.tokenize("SELECT * FROM \"t\" WHERE x IN [1, 2"))
                .isInstanceOf(PartiQlParseException.class)
                .hasMessage("error.dynamodb.unbalanced");
    }
}
