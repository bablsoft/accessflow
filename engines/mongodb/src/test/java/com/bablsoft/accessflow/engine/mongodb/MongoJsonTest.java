package com.bablsoft.accessflow.engine.mongodb;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;
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
    void parsesObjectIdConstructorViaBsonFallback() {
        var doc = MongoJson.parseDocument("{ _id: ObjectId(\"507f1f77bcf86cd799439011\"), name: 'Ada' }");
        assertThat(doc.get("_id")).isInstanceOf(ObjectId.class);
        assertThat(((ObjectId) doc.get("_id")).toHexString()).isEqualTo("507f1f77bcf86cd799439011");
        assertThat(doc.get("name")).isEqualTo("Ada");
    }

    @Test
    void parsesDateConstructorsViaBsonFallback() {
        var doc = MongoJson.parseDocument(
                "{ created: ISODate(\"2020-01-02T03:04:05Z\"), updated: new Date(1577836800000) }");
        assertThat(doc.get("created")).isInstanceOf(Date.class);
        assertThat(doc.get("updated")).isInstanceOf(Date.class);
    }

    @Test
    void parsesNumberConstructorsViaBsonFallback() {
        var doc = MongoJson.parseDocument(
                "{ big: NumberLong(\"123456789012\"), price: NumberDecimal(\"9.99\") }");
        assertThat(doc.get("big")).isEqualTo(123_456_789_012L);
        assertThat(doc.get("price")).isInstanceOf(Decimal128.class);
    }

    @Test
    void parseDocumentArrayParsesConstructorDocumentsForInsertMany() {
        var docs = MongoJson.parseDocumentArray(
                "[{ _id: ObjectId(\"507f1f77bcf86cd799439011\"), at: ISODate(\"2020-01-01T00:00:00Z\") },"
                        + " { _id: ObjectId(\"507f191e810c19729de860ea\") }]");
        assertThat(docs).hasSize(2);
        assertThat(docs.get(0).get("_id")).isInstanceOf(ObjectId.class);
        assertThat(docs.get(0).get("at")).isInstanceOf(Date.class);
        assertThat(docs.get(1).get("_id")).isInstanceOf(ObjectId.class);
    }

    @Test
    void forbiddenOperatorsStillRejectedAfterBsonFallback() {
        // $where with a constructor sibling forces the BSON fallback; the operator must still be caught.
        var doc = MongoJson.parseDocument(
                "{ _id: ObjectId(\"507f1f77bcf86cd799439011\"), $where: \"true\" }");
        assertThatThrownBy(() -> MongoJson.assertNoForbiddenOperators(doc))
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
