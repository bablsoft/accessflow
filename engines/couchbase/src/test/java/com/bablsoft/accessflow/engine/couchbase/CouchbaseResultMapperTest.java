package com.bablsoft.accessflow.engine.couchbase;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.ColumnMasker;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CouchbaseResultMapperTest {

    private final CouchbaseResultMapper mapper = new CouchbaseResultMapper();

    private static Map<String, Object> doc(Object... kv) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    void columnsAreTheOrderedUnionAcrossRows() {
        var result = mapper.materialize(List.of(
                        doc("a", 1, "b", "x"),
                        doc("b", "y", "c", true)),
                null, 10, Duration.ZERO, List.of(), List.of());
        assertThat(result.columns()).extracting("name").containsExactly("a", "b", "c");
        assertThat(result.rows().get(0)).containsExactly(1, "x", null);
        assertThat(result.rows().get(1)).containsExactly(null, "y", true);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void detectsTruncationBeyondMaxRows() {
        var result = mapper.materialize(List.of(doc("a", 1), doc("a", 2), doc("a", 3)),
                null, 2, Duration.ZERO, List.of(), List.of());
        assertThat(result.rows()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void unwrapsSelectStarWrapperRows() {
        var result = mapper.materialize(List.of(
                        doc("users", doc("name", "Ada", "team", "eng")),
                        doc("users", doc("name", "Bo", "team", "ops"))),
                "users", 10, Duration.ZERO, List.of(), List.of());
        assertThat(result.columns()).extracting("name").containsExactly("name", "team");
        assertThat(result.rows().get(0)).containsExactly("Ada", "eng");
    }

    @Test
    void doesNotUnwrapProjectedOrMixedRows() {
        // Single-key rows whose value is not an object (projection of one scalar field).
        var projected = mapper.materialize(List.of(doc("users", "Ada")), "users", 10,
                Duration.ZERO, List.of(), List.of());
        assertThat(projected.columns()).extracting("name").containsExactly("users");

        // Key differs from the unwrap key.
        var otherKey = mapper.materialize(List.of(doc("address", doc("city", "Paris"))), "users",
                10, Duration.ZERO, List.of(), List.of());
        assertThat(otherKey.columns()).extracting("name").containsExactly("address");

        // Mixed pages never unwrap.
        var mixed = mapper.materialize(List.of(
                        doc("users", doc("name", "Ada")),
                        doc("users", doc("name", "Bo"), "extra", 1)),
                "users", 10, Duration.ZERO, List.of(), List.of());
        assertThat(mixed.columns()).extracting("name").containsExactly("users", "extra");
    }

    @Test
    void rawScalarRowsBecomeAValueColumn() {
        var result = mapper.materialize(java.util.Arrays.asList(1, "two", null, List.of(1, 2)),
                null, 10, Duration.ZERO, List.of(), List.of());
        assertThat(result.columns()).extracting("name").containsExactly("value");
        assertThat(result.rows()).extracting(r -> r.get(0))
                .containsExactly(1, "two", null, List.of(1, 2));
    }

    @Test
    void restrictedColumnsAreFullyMasked() {
        var result = mapper.materialize(List.of(doc("name", "Ada", "salary", 100)), null, 10,
                Duration.ZERO, List.of("salary"), List.of());
        assertThat(result.rows().get(0).get(1)).isEqualTo(ColumnMasker.FULL_MASK);
        assertThat(result.columns().get(1).restricted()).isTrue();
        assertThat(result.appliedMaskingPolicyIds()).isEmpty();
    }

    @Test
    void maskDirectiveWinsOverRestrictedAndRecordsPolicyId() {
        var policyId = UUID.randomUUID();
        var mask = new ColumnMaskDirective("users.email", MaskingStrategy.EMAIL, Map.of(), policyId);
        var result = mapper.materialize(List.of(doc("email", "ada@x.io")), null, 10,
                Duration.ZERO, List.of("email"), List.of(mask));
        assertThat(result.rows().get(0).get(0)).isEqualTo("a***@x.io");
        assertThat(result.appliedMaskingPolicyIds()).containsExactly(policyId);
    }

    @Test
    void collectionQualifiedRefBeatsBareRef() {
        var bare = new ColumnMaskDirective("email", MaskingStrategy.FULL, Map.of(),
                UUID.randomUUID());
        var qualified = new ColumnMaskDirective("users.email", MaskingStrategy.EMAIL, Map.of(),
                UUID.randomUUID());
        var result = mapper.materialize(List.of(doc("email", "ada@x.io")), null, 10,
                Duration.ZERO, List.of(), List.of(bare, qualified));
        assertThat(result.rows().get(0).get(0)).isEqualTo("a***@x.io");
        assertThat(result.appliedMaskingPolicyIds()).containsExactly(qualified.policyId());
    }

    @Test
    void masksApplyAfterTheSelectStarUnwrap() {
        var mask = new ColumnMaskDirective("users.email", MaskingStrategy.EMAIL, Map.of(),
                UUID.randomUUID());
        var result = mapper.materialize(
                List.of(doc("users", doc("name", "Ada", "email", "ada@x.io"))),
                "users", 10, Duration.ZERO, List.of(), List.of(mask));
        assertThat(result.rows().get(0).get(1)).isEqualTo("a***@x.io");
    }

    @Test
    void nullValuesStayNullEvenWhenMasked() {
        var result = mapper.materialize(List.of(doc("email", null)), null, 10, Duration.ZERO,
                List.of("email"), List.of());
        assertThat(result.rows().get(0).get(0)).isNull();
    }

    @Test
    void typeNamesFollowJsonTypes() {
        var result = mapper.materialize(List.of(doc(
                        "s", "x", "n", 1, "b", true, "o", doc("k", 1), "a", List.of(1), "z", null)),
                null, 10, Duration.ZERO, List.of(), List.of());
        assertThat(result.columns()).extracting("typeName")
                .containsExactly("string", "number", "boolean", "object", "array", "null");
    }
}
