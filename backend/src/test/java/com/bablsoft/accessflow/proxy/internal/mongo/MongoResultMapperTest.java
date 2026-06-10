package com.bablsoft.accessflow.proxy.internal.mongo;

import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.proxy.api.ColumnMaskDirective;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MongoResultMapperTest {

    private final MongoResultMapper mapper = new MongoResultMapper();

    @Test
    void buildsColumnUnionAcrossHeterogeneousDocuments() {
        var docs = List.of(
                new Document("_id", 1).append("name", "Ada"),
                new Document("_id", 2).append("age", 30));
        var result = mapper.materialize(docs, 100, Duration.ZERO, List.of(), List.of());
        assertThat(result.columns()).extracting("name").containsExactly("_id", "name", "age");
        // Missing fields align to null.
        assertThat(result.rows().get(1)).containsExactly(2, null, 30);
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void truncatesWhenMoreThanMaxRows() {
        var docs = List.of(new Document("a", 1), new Document("a", 2), new Document("a", 3));
        var result = mapper.materialize(docs, 2, Duration.ZERO, List.of(), List.of());
        assertThat(result.rows()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void normalizesBsonScalarTypes() {
        var oid = new ObjectId();
        var date = new Date(0);
        var docs = List.of(new Document("id", oid)
                .append("amount", new Decimal128(new BigDecimal("9.99")))
                .append("when", date));
        var result = mapper.materialize(docs, 10, Duration.ZERO, List.of(), List.of());
        var row = result.rows().get(0);
        assertThat(row.get(0)).isEqualTo(oid.toHexString());
        assertThat(row.get(1)).isEqualTo(new BigDecimal("9.99"));
        assertThat(row.get(2)).isEqualTo("1970-01-01T00:00Z");
    }

    @Test
    void preservesNestedDocumentsAndArrays() {
        var docs = List.of(new Document("profile",
                new Document("city", "NYC")).append("tags", List.of("a", "b")));
        var result = mapper.materialize(docs, 10, Duration.ZERO, List.of(), List.of());
        assertThat(result.rows().get(0).get(0)).isInstanceOf(Map.class);
        assertThat(result.rows().get(0).get(1)).isEqualTo(List.of("a", "b"));
    }

    @Test
    void appliesFullMaskFromRestrictedColumns() {
        var docs = List.of(new Document("email", "ada@example.com"));
        var result = mapper.materialize(docs, 10, Duration.ZERO, List.of("email"), List.of());
        assertThat(result.columns().get(0).restricted()).isTrue();
        assertThat(result.rows().get(0).get(0)).isEqualTo("***");
    }

    @Test
    void appliesPartialMaskDirectiveAndRecordsPolicy() {
        var policyId = UUID.randomUUID();
        var docs = List.of(new Document("email", "ada@example.com"));
        var mask = new ColumnMaskDirective("users.email", MaskingStrategy.EMAIL, Map.of(), policyId);
        var result = mapper.materialize(docs, 10, Duration.ZERO, List.of(), List.of(mask));
        assertThat(result.rows().get(0).get(0)).isEqualTo("a***@example.com");
        assertThat(result.columns().get(0).restricted()).isTrue();
        assertThat(result.appliedMaskingPolicyIds()).containsExactly(policyId);
    }
}
