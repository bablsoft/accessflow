package com.bablsoft.accessflow.engine.dynamodb;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.ResultColumn;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DynamoDbResultMapperTest {

    private final DynamoDbResultMapper mapper = new DynamoDbResultMapper();

    private static Map<String, AttributeValue> item(Object... pairs) {
        var map = new java.util.LinkedHashMap<String, AttributeValue>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (AttributeValue) pairs[i + 1]);
        }
        return map;
    }

    private static int columnIndex(List<ResultColumn> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new AssertionError("column not found: " + name);
    }

    @Test
    void convertsScalarsToJsonFriendlyValues() {
        var items = List.of(item("id", AttributeValue.fromS("1"),
                "plays", AttributeValue.fromN("5"),
                "active", AttributeValue.fromBool(true)));
        var result = mapper.materialize(items, 10, Duration.ZERO, List.of(), List.of());
        assertThat(result.columns()).extracting(ResultColumn::name)
                .containsExactly("id", "plays", "active");
        var row = result.rows().get(0);
        assertThat(row.get(columnIndex(result.columns(), "id"))).isEqualTo("1");
        assertThat(row.get(columnIndex(result.columns(), "plays"))).isEqualTo(new BigDecimal("5"));
        assertThat(row.get(columnIndex(result.columns(), "active"))).isEqualTo(true);
    }

    @Test
    void preservesNestedMapsAndLists() {
        var profile = AttributeValue.fromM(Map.of("ssn", AttributeValue.fromS("123"),
                "phone", AttributeValue.fromS("555")));
        var items = List.of(item("id", AttributeValue.fromS("1"), "profile", profile,
                "tags", AttributeValue.fromL(List.of(AttributeValue.fromS("a"), AttributeValue.fromS("b")))));
        var result = mapper.materialize(items, 10, Duration.ZERO, List.of(), List.of());
        var row = result.rows().get(0);
        assertThat(row.get(columnIndex(result.columns(), "profile"))).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var profileValue = (Map<String, Object>) row.get(columnIndex(result.columns(), "profile"));
        assertThat(profileValue).containsEntry("ssn", "123").containsEntry("phone", "555");
        assertThat(row.get(columnIndex(result.columns(), "tags"))).isEqualTo(List.of("a", "b"));
    }

    @Test
    void restrictedColumnIsFullyMasked() {
        var items = List.of(item("email", AttributeValue.fromS("a@b.com")));
        var result = mapper.materialize(items, 10, Duration.ZERO, List.of("email"), List.of());
        assertThat(result.columns().get(0).restricted()).isTrue();
        assertThat(result.rows().get(0).get(0)).isEqualTo("***");
    }

    @Test
    void appliesEmailMaskDirectiveAndRecordsPolicy() {
        var policyId = UUID.randomUUID();
        var items = List.of(item("email", AttributeValue.fromS("john@example.com")));
        var result = mapper.materialize(items, 10, Duration.ZERO, List.of(),
                List.of(new ColumnMaskDirective("email", MaskingStrategy.EMAIL, Map.of(), policyId)));
        assertThat(result.rows().get(0).get(0)).isEqualTo("j***@example.com");
        assertThat(result.appliedMaskingPolicyIds()).contains(policyId);
    }

    @Test
    void masksNestedLeafByDotPathLeavingSiblingsIntact() {
        var policyId = UUID.randomUUID();
        var profile = AttributeValue.fromM(Map.of("ssn", AttributeValue.fromS("123456789"),
                "phone", AttributeValue.fromS("555-0100")));
        var items = List.of(item("id", AttributeValue.fromS("1"), "profile", profile));
        var result = mapper.materialize(items, 10, Duration.ZERO, List.of(),
                List.of(new ColumnMaskDirective("profile.ssn", MaskingStrategy.FULL, Map.of(), policyId)));
        @SuppressWarnings("unchecked")
        var masked = (Map<String, Object>) result.rows().get(0)
                .get(columnIndex(result.columns(), "profile"));
        assertThat(masked).containsEntry("ssn", "***").containsEntry("phone", "555-0100");
        assertThat(result.appliedMaskingPolicyIds()).contains(policyId);
    }

    @Test
    void detectsTruncationAtMaxRows() {
        var items = List.of(
                item("id", AttributeValue.fromS("1")),
                item("id", AttributeValue.fromS("2")),
                item("id", AttributeValue.fromS("3")));
        var result = mapper.materialize(items, 2, Duration.ZERO, List.of(), List.of());
        assertThat(result.truncated()).isTrue();
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.rows()).hasSize(2);
    }

    @Test
    void unionsHeterogeneousAttributeNames() {
        var items = List.of(
                item("id", AttributeValue.fromS("1"), "a", AttributeValue.fromS("x")),
                item("id", AttributeValue.fromS("2"), "b", AttributeValue.fromS("y")));
        var result = mapper.materialize(items, 10, Duration.ZERO, List.of(), List.of());
        assertThat(result.columns()).extracting(ResultColumn::name).containsExactly("id", "a", "b");
        // Absent attribute renders as null.
        assertThat(result.rows().get(0).get(columnIndex(result.columns(), "b"))).isNull();
    }
}
