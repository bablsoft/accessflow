package com.bablsoft.accessflow.engine.neo4j;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class Neo4jResultMapperTest {

    private final Neo4jResultMapper mapper = new Neo4jResultMapper();
    private static final UUID POLICY = UUID.randomUUID();

    private static Map<String, Object> node(String label, Map<String, Object> properties) {
        var node = new LinkedHashMap<String, Object>();
        node.put("_elementId", "4:abc:1");
        node.put(Neo4jResultMapper.LABELS_KEY, List.of(label));
        node.putAll(properties);
        return node;
    }

    @Test
    void labelQualifiedMaskRedactsOnlyMatchingPropertyInsideNode() {
        var user = node("User", Map.of("email", "alice@example.com", "name", "Alice"));
        var result = mapper.materialize(List.of("u"), List.of(List.of(user)), 100, Duration.ZERO,
                List.of(), List.of(new ColumnMaskDirective("User.email", MaskingStrategy.EMAIL,
                        Map.of(), POLICY)));

        @SuppressWarnings("unchecked")
        var masked = (Map<String, Object>) result.rows().get(0).get(0);
        assertThat(masked.get("email")).isEqualTo("a***@example.com");
        assertThat(masked.get("name")).isEqualTo("Alice");
        assertThat(result.columns().get(0).restricted()).isTrue();
        assertThat(result.appliedMaskingPolicyIds()).containsExactly(POLICY);
    }

    @Test
    void doesNotMaskNodeOfADifferentLabel() {
        var account = node("Account", Map.of("email", "ops@example.com"));
        var result = mapper.materialize(List.of("a"), List.of(List.of(account)), 100, Duration.ZERO,
                List.of(), List.of(new ColumnMaskDirective("User.email", MaskingStrategy.FULL,
                        Map.of(), POLICY)));

        @SuppressWarnings("unchecked")
        var node = (Map<String, Object>) result.rows().get(0).get(0);
        assertThat(node.get("email")).isEqualTo("ops@example.com");
        assertThat(result.columns().get(0).restricted()).isFalse();
    }

    @Test
    void barePropertyMaskRedactsScalarColumnAndNestedProperty() {
        var profile = new LinkedHashMap<String, Object>();
        profile.put("ssn", "123-45-6789");
        var result = mapper.materialize(List.of("ssn", "p"),
                List.of(List.of("999-99-9999", profile)), 100, Duration.ZERO,
                List.of(), List.of(new ColumnMaskDirective("ssn", MaskingStrategy.FULL, Map.of(), POLICY)));

        assertThat(result.rows().get(0).get(0)).isEqualTo("***");
        @SuppressWarnings("unchecked")
        var nested = (Map<String, Object>) result.rows().get(0).get(1);
        assertThat(nested.get("ssn")).isEqualTo("***");
    }

    @Test
    void restrictedColumnsDefaultToFullMask() {
        var user = node("User", Map.of("email", "alice@example.com"));
        var result = mapper.materialize(List.of("u"), List.of(List.of(user)), 100, Duration.ZERO,
                List.of("User.email"), List.of());

        @SuppressWarnings("unchecked")
        var masked = (Map<String, Object>) result.rows().get(0).get(0);
        assertThat(masked.get("email")).isEqualTo("***");
    }

    @Test
    void leavesUnmatchedValuesUntouched() {
        var result = mapper.materialize(List.of("n"), List.of(List.of("Alice")), 100, Duration.ZERO,
                List.of(), List.of());
        assertThat(result.rows().get(0).get(0)).isEqualTo("Alice");
        assertThat(result.columns().get(0).restricted()).isFalse();
        assertThat(result.appliedMaskingPolicyIds()).isEmpty();
    }

    @Test
    void marksTruncationBeyondMaxRows() {
        var rows = List.of(List.<Object>of("a"), List.<Object>of("b"), List.<Object>of("c"));
        var result = mapper.materialize(List.of("n"), rows, 2, Duration.ZERO, List.of(), List.of());
        assertThat(result.truncated()).isTrue();
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rowCount()).isEqualTo(2);
    }

    @Test
    void reportsColumnTypeNames() {
        var node = node("User", Map.of());
        var result = mapper.materialize(List.of("n", "u"),
                List.of(List.of(42, node)), 100, Duration.ZERO, List.of(), List.of());
        assertThat(result.columns().get(0).typeName()).isEqualTo("number");
        assertThat(result.columns().get(1).typeName()).isEqualTo("node");
    }
}
