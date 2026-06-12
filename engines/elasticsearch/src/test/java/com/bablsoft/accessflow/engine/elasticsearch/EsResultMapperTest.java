package com.bablsoft.accessflow.engine.elasticsearch;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.ResultColumn;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EsResultMapperTest {

    private final EsResultMapper mapper = new EsResultMapper();

    private static JsonNode hits(String json) {
        return EsJson.parse(json);
    }

    private static List<String> columnNames(List<ResultColumn> columns) {
        return columns.stream().map(ResultColumn::name).toList();
    }

    @Test
    void buildsMetaFirstColumnsThenSourceFieldUnion() {
        var result = mapper.materializeSearch(hits(
                        "[{\"_id\":\"1\",\"_index\":\"logs\",\"_score\":1.0,\"_source\":{\"a\":1}},"
                                + "{\"_id\":\"2\",\"_index\":\"logs\",\"_score\":0.5,\"_source\":{\"b\":2}}]"),
                false, 100, Duration.ofMillis(1), List.of(), List.of());
        assertThat(columnNames(result.columns())).containsExactly("_id", "_index", "_score", "a", "b");
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void omitsScoreColumnWhenSortIsPresent() {
        var result = mapper.materializeSearch(hits(
                        "[{\"_id\":\"1\",\"_index\":\"logs\",\"_source\":{\"a\":1}}]"),
                true, 100, Duration.ofMillis(1), List.of(), List.of());
        assertThat(columnNames(result.columns())).containsExactly("_id", "_index", "a");
    }

    @Test
    void flagsTruncationWhenMoreThanMaxRowsFetched() {
        var result = mapper.materializeSearch(hits(
                        "[{\"_id\":\"1\",\"_index\":\"logs\",\"_source\":{}},"
                                + "{\"_id\":\"2\",\"_index\":\"logs\",\"_source\":{}}]"),
                false, 1, Duration.ofMillis(1), List.of(), List.of());
        assertThat(result.truncated()).isTrue();
        assertThat(result.rowCount()).isEqualTo(1);
    }

    @Test
    void masksNestedSourceFieldByDotPathAndRecordsPolicyId() {
        var policyId = UUID.randomUUID();
        var mask = new ColumnMaskDirective("user.email", MaskingStrategy.EMAIL, Map.of(), policyId);
        var result = mapper.materializeSearch(hits(
                        "[{\"_id\":\"1\",\"_index\":\"logs\","
                                + "\"_source\":{\"name\":\"Bob\",\"user\":{\"email\":\"bob@x.io\",\"id\":7}}}]"),
                true, 100, Duration.ofMillis(1), List.of(), List.of(mask));
        int userCol = columnNames(result.columns()).indexOf("user");
        @SuppressWarnings("unchecked")
        var user = (Map<String, Object>) result.rows().get(0).get(userCol);
        assertThat(user.get("email")).isEqualTo("b***@x.io");
        assertThat(user.get("id")).isEqualTo(7);
        assertThat(result.appliedMaskingPolicyIds()).containsExactly(policyId);
        assertThat(result.columns().get(userCol).restricted()).isTrue();
    }

    @Test
    void appliesFullMaskToATopLevelField() {
        var mask = new ColumnMaskDirective("ssn", MaskingStrategy.FULL, Map.of(), UUID.randomUUID());
        var result = mapper.materializeSearch(hits(
                        "[{\"_id\":\"1\",\"_index\":\"logs\",\"_source\":{\"ssn\":\"123\"}}]"),
                true, 100, Duration.ofMillis(1), List.of(), List.of(mask));
        int col = columnNames(result.columns()).indexOf("ssn");
        assertThat(result.rows().get(0).get(col)).isEqualTo("***");
    }

    @Test
    void countResultIsASingleNumericRow() {
        var result = mapper.materializeCount(42, Duration.ofMillis(1));
        assertThat(columnNames(result.columns())).containsExactly("count");
        assertThat(result.rows()).containsExactly(List.of(42L));
    }
}
