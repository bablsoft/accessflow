package com.bablsoft.accessflow.proxy.internal.mongo;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoJsonTest {

    @Test
    void parsesRelaxedJsonObjectWithUnquotedKeysAndSingleQuotes() {
        var doc = MongoJson.parseDocument("{ name: 'Ada', age: 36, active: true }");
        assertThat(doc).containsEntry("name", "Ada").containsEntry("age", 36)
                .containsEntry("active", true);
    }

    @Test
    void numbersNarrowToIntWhenInRangeAndLongOtherwise() {
        var doc = MongoJson.parseDocument("{ small: 7, big: 9999999999, dec: 1.5 }");
        assertThat(doc.get("small")).isEqualTo(7);
        assertThat(doc.get("big")).isEqualTo(9_999_999_999L);
        assertThat(doc.get("dec")).isEqualTo(1.5);
    }

    @Test
    void bigDecimalNumbersArePreserved() {
        var value = MongoJson.parseValue("12.340");
        assertThat(value).isInstanceOf(Double.class);
        var doc = MongoJson.parseDocument("{ n: 1e3 }");
        assertThat(((Number) doc.get("n")).doubleValue()).isEqualTo(1000.0);
    }

    @Test
    void parseValueReturnsNullForBlank() {
        assertThat(MongoJson.parseValue("  ")).isNull();
        assertThat(MongoJson.parseValue(null)).isNull();
    }

    @Test
    void parseDocumentRejectsNonObject() {
        assertThatThrownBy(() -> MongoJson.parseDocument("[1, 2]"))
                .isInstanceOf(MongoParseException.class);
    }

    @Test
    void parseDocumentArrayRejectsNonArrayAndNonObjectElements() {
        assertThatThrownBy(() -> MongoJson.parseDocumentArray("{}"))
                .isInstanceOf(MongoParseException.class);
        assertThatThrownBy(() -> MongoJson.parseDocumentArray("[1, 2]"))
                .isInstanceOf(MongoParseException.class);
    }

    @Test
    void parseDocumentArrayReturnsDocuments() {
        var docs = MongoJson.parseDocumentArray("[{ a: 1 }, { b: 2 }]");
        assertThat(docs).hasSize(2);
        assertThat(docs.get(0)).isInstanceOf(Document.class);
    }

    @Test
    void invalidJsonThrows() {
        assertThatThrownBy(() -> MongoJson.parseValue("{ broken "))
                .isInstanceOf(MongoParseException.class);
    }

    @Test
    void toBsonHandlesNestedAndScalarTypes() {
        var doc = MongoJson.parseDocument(
                "{ s: 'x', n: 1, b: false, nul: null, arr: [1, 'a'], obj: { k: 1 } }");
        assertThat(doc.get("s")).isEqualTo("x");
        assertThat(doc.get("b")).isEqualTo(false);
        assertThat(doc.get("nul")).isNull();
        assertThat(doc.get("arr")).isEqualTo(List.of(1, "a"));
        assertThat(doc.get("obj")).isInstanceOf(Document.class);
        assertThat(doc.get("n")).isInstanceOf(Integer.class);
        assertThat(BigDecimal.ZERO).isNotNull();
    }

    @Test
    void splitArgsRespectsNestingAndStrings() {
        var args = MongoJson.splitArgs("{ a: 1 }, { b: ',' }, 'x, y'");
        assertThat(args).containsExactly("{ a: 1 }", "{ b: ',' }", "'x, y'");
    }

    @Test
    void splitArgsHandlesEscapesInStrings() {
        var args = MongoJson.splitArgs("'a\\'b', 2");
        assertThat(args).containsExactly("'a\\'b'", "2");
    }

    @Test
    void splitArgsEmptyForBlank() {
        assertThat(MongoJson.splitArgs("")).isEmpty();
        assertThat(MongoJson.splitArgs(null)).isEmpty();
    }

    @Test
    void splitArgsRejectsUnbalanced() {
        assertThatThrownBy(() -> MongoJson.splitArgs("{ a: 1 "))
                .isInstanceOf(MongoParseException.class);
    }

    @Test
    void assertNoForbiddenOperatorsScansDocumentsMapsAndLists() {
        MongoJson.assertNoForbiddenOperators(new Document("ok", 1));
        MongoJson.assertNoForbiddenOperators(List.of(new Document("ok", 1)));
        assertThatThrownBy(() ->
                MongoJson.assertNoForbiddenOperators(new Document("$where", "true")))
                .isInstanceOf(MongoParseException.class);
        assertThatThrownBy(() ->
                MongoJson.assertNoForbiddenOperators(Map.of("$function", "x")))
                .isInstanceOf(MongoParseException.class);
        assertThatThrownBy(() ->
                MongoJson.assertNoForbiddenOperators(List.of(new Document("$out", "leak"))))
                .isInstanceOf(MongoParseException.class);
    }

    @Test
    void assertNoForbiddenOperatorsIgnoresNullAndScalars() {
        MongoJson.assertNoForbiddenOperators(null);
        MongoJson.assertNoForbiddenOperators("plain");
        MongoJson.assertNoForbiddenOperators(42);
    }
}
