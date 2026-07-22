package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.ColumnMasker;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.FieldValue;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.StandardSQLTypeName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BigQueryResultMapperTest {

    private final BigQueryResultMapper mapper = new BigQueryResultMapper();

    private static final FieldList FIELDS = FieldList.of(
            Field.of("id", StandardSQLTypeName.INT64),
            Field.of("name", StandardSQLTypeName.STRING),
            Field.of("score", StandardSQLTypeName.FLOAT64),
            Field.of("balance", StandardSQLTypeName.NUMERIC),
            Field.of("active", StandardSQLTypeName.BOOL),
            Field.of("created", StandardSQLTypeName.TIMESTAMP),
            Field.newBuilder("tags", StandardSQLTypeName.STRING).setMode(Field.Mode.REPEATED).build(),
            Field.of("profile", StandardSQLTypeName.STRUCT,
                    Field.of("ssn", StandardSQLTypeName.STRING),
                    Field.of("phone", StandardSQLTypeName.STRING)));

    private static FieldValue primitive(String value) {
        return FieldValue.of(FieldValue.Attribute.PRIMITIVE, value);
    }

    private static FieldValueList row(String id, String name, String ssn, String phone) {
        var profileFields = FieldList.of(
                Field.of("ssn", StandardSQLTypeName.STRING),
                Field.of("phone", StandardSQLTypeName.STRING));
        return FieldValueList.of(List.of(
                primitive(id),
                primitive(name),
                primitive("1.5"),
                primitive("42.10"),
                primitive("true"),
                primitive("1700000000.0"),
                FieldValue.of(FieldValue.Attribute.REPEATED,
                        List.of(primitive("a"), primitive("b"))),
                FieldValue.of(FieldValue.Attribute.RECORD, FieldValueList.of(
                        List.of(primitive(ssn), primitive(phone)), profileFields))), FIELDS);
    }

    @Test
    void mapsColumnsWithTypeNamesAndSqlTypes() {
        var result = mapper.materialize(FIELDS, List.of(row("7", "Ada", "111", "555")), 10,
                Duration.ofMillis(5), List.of(), List.of());
        assertThat(result.columns()).extracting("name")
                .containsExactly("id", "name", "score", "balance", "active", "created", "tags",
                        "profile");
        assertThat(result.columns()).extracting("typeName")
                .containsExactly("INT64", "STRING", "FLOAT64", "NUMERIC", "BOOL", "TIMESTAMP",
                        "ARRAY<STRING>", "STRUCT");
        assertThat(result.columns()).extracting("jdbcType")
                .containsExactly(Types.BIGINT, Types.VARCHAR, Types.DOUBLE, Types.NUMERIC,
                        Types.BOOLEAN, Types.TIMESTAMP, Types.ARRAY, Types.STRUCT);
    }

    @Test
    void convertsValuesToJsonFriendlyJavaTypes() {
        var result = mapper.materialize(FIELDS, List.of(row("7", "Ada", "111", "555")), 10,
                Duration.ofMillis(5), List.of(), List.of());
        var row = result.rows().get(0);
        assertThat(row.get(0)).isEqualTo(7L);
        assertThat(row.get(1)).isEqualTo("Ada");
        assertThat(row.get(2)).isEqualTo(1.5d);
        assertThat(row.get(3)).isEqualTo(new BigDecimal("42.10"));
        assertThat(row.get(4)).isEqualTo(true);
        assertThat(row.get(5)).isInstanceOf(Instant.class)
                .extracting(v -> ((Instant) v).getEpochSecond()).isEqualTo(1700000000L);
        assertThat(row.get(6)).isEqualTo(List.of("a", "b"));
        assertThat(row.get(7)).isEqualTo(Map.of("ssn", "111", "phone", "555"));
    }

    @Test
    void nullValuesStayNull() {
        var fields = FieldList.of(Field.of("name", StandardSQLTypeName.STRING));
        var rows = List.of(FieldValueList.of(
                List.of(FieldValue.of(FieldValue.Attribute.PRIMITIVE, null)), fields));
        var result = mapper.materialize(fields, rows, 10, Duration.ZERO, List.of(), List.of());
        assertThat(result.rows().get(0).get(0)).isNull();
    }

    @Test
    void truncatesAtMaxRowsAndFlags() {
        var rows = List.of(row("1", "a", "x", "y"), row("2", "b", "x", "y"),
                row("3", "c", "x", "y"));
        var result = mapper.materialize(FIELDS, rows, 2, Duration.ZERO, List.of(), List.of());
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.truncated()).isTrue();
        var exact = mapper.materialize(FIELDS, rows.subList(0, 2), 2, Duration.ZERO,
                List.of(), List.of());
        assertThat(exact.truncated()).isFalse();
    }

    @Test
    void restrictedColumnIsFullyMaskedAndFlagged() {
        var result = mapper.materialize(FIELDS, List.of(row("7", "Ada", "111", "555")), 10,
                Duration.ZERO, List.of("name"), List.of());
        assertThat(result.rows().get(0).get(1)).isEqualTo(ColumnMasker.FULL_MASK);
        assertThat(result.columns().get(1).restricted()).isTrue();
        assertThat(result.columns().get(0).restricted()).isFalse();
    }

    @Test
    void maskDirectiveAppliesStrategyAndRecordsPolicyId() {
        var mask = new ColumnMaskDirective("name", MaskingStrategy.PARTIAL,
                Map.of("visible_suffix", "2"), UUID.randomUUID());
        var result = mapper.materialize(FIELDS, List.of(row("7", "Adamant", "111", "555")), 10,
                Duration.ZERO, List.of(), List.of(mask));
        assertThat(result.rows().get(0).get(1)).isEqualTo("*****nt");
        assertThat(result.appliedMaskingPolicyIds()).containsExactly(mask.policyId());
    }

    @Test
    void dotPathMaskRedactsOnlyTheNestedRecordLeaf() {
        var mask = new ColumnMaskDirective("profile.ssn", MaskingStrategy.FULL, Map.of(),
                UUID.randomUUID());
        var result = mapper.materialize(FIELDS, List.of(row("7", "Ada", "111-22-3333", "555-01")),
                10, Duration.ZERO, List.of(), List.of(mask));
        @SuppressWarnings("unchecked")
        var profile = (Map<String, Object>) result.rows().get(0).get(7);
        assertThat(profile.get("ssn")).isEqualTo(ColumnMasker.FULL_MASK);
        assertThat(profile.get("phone")).isEqualTo("555-01");
        assertThat(result.columns().get(7).restricted()).isTrue();
        assertThat(result.appliedMaskingPolicyIds()).containsExactly(mask.policyId());
    }

    @Test
    void wholeColumnFullMaskCollapsesRecordAndRepeatedValues() {
        var result = mapper.materialize(FIELDS, List.of(row("7", "Ada", "111", "555")), 10,
                Duration.ZERO, List.of("profile", "tags"), List.of());
        assertThat(result.rows().get(0).get(7)).isEqualTo(ColumnMasker.FULL_MASK);
        assertThat(result.rows().get(0).get(6)).isEqualTo(ColumnMasker.FULL_MASK);
    }

    @Test
    void nonFullWholeColumnMaskRecursesIntoStructure() {
        var mask = new ColumnMaskDirective("tags", MaskingStrategy.HASH, Map.of(),
                UUID.randomUUID());
        var result = mapper.materialize(FIELDS, List.of(row("7", "Ada", "111", "555")), 10,
                Duration.ZERO, List.of(), List.of(mask));
        @SuppressWarnings("unchecked")
        var tags = (List<Object>) result.rows().get(0).get(6);
        assertThat(tags).hasSize(2);
        assertThat(tags.get(0)).asString().hasSize(64).isNotEqualTo("a");
    }

    @Test
    void directiveOnBareColumnWinsOverRestrictedEntry() {
        var mask = new ColumnMaskDirective("name", MaskingStrategy.EMAIL, Map.of(),
                UUID.randomUUID());
        var result = mapper.materialize(FIELDS,
                List.of(row("7", "ada@example.com", "111", "555")), 10, Duration.ZERO,
                List.of("name"), List.of(mask));
        assertThat(result.rows().get(0).get(1)).isEqualTo("a***@example.com");
    }

    @Test
    void maskingIsCaseInsensitiveOnColumnNames() {
        var result = mapper.materialize(FIELDS, List.of(row("7", "Ada", "111", "555")), 10,
                Duration.ZERO, List.of("NAME", "Profile.SSN"), List.of());
        assertThat(result.rows().get(0).get(1)).isEqualTo(ColumnMasker.FULL_MASK);
        @SuppressWarnings("unchecked")
        var profile = (Map<String, Object>) result.rows().get(0).get(7);
        assertThat(profile.get("ssn")).isEqualTo(ColumnMasker.FULL_MASK);
    }
}
