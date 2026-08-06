package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalFeatureVectorTest {

    private static double[] zeros() {
        return new double[ApprovalFeatureVector.FEATURE_SCHEMA_V1.size()];
    }

    private static int indexOf(String feature) {
        return ApprovalFeatureVector.FEATURE_SCHEMA_V1.indexOf(feature);
    }

    @Test
    void schemaV1ListsTwentyOneFeaturesInTheDocumentedOrder() {
        assertThat(ApprovalFeatureVector.FEATURE_SCHEMA_V1).containsExactly(
                "risk_score",
                "risk_level_LOW",
                "risk_level_MEDIUM",
                "risk_level_HIGH",
                "risk_level_CRITICAL",
                "issue_count",
                "ai_missing",
                "estimated_rows_log10",
                "affected_row_count_log10",
                "estimated_cost_log10",
                "seq_scan",
                "estimate_missing",
                "query_type_SELECT",
                "query_type_INSERT",
                "query_type_UPDATE",
                "query_type_DELETE",
                "query_type_DDL",
                "transactional",
                "off_hours",
                "submitter_approval_rate",
                "datasource_approval_rate");
        assertThat(ApprovalFeatureVector.FEATURE_SCHEMA_V1).hasSize(21);
    }

    @Test
    void schemaV1FeatureNamesAreUnique() {
        var unique = new HashSet<>(ApprovalFeatureVector.FEATURE_SCHEMA_V1);
        assertThat(unique).hasSameSizeAs(ApprovalFeatureVector.FEATURE_SCHEMA_V1);
    }

    @Test
    void schemaVersionIsOne() {
        assertThat(ApprovalFeatureVector.SCHEMA_VERSION).isEqualTo(1);
    }

    @Test
    void everyEnumConstantThatNeedsASlotHasOne() {
        // A new QueryType or RiskLevel constant is a schema-v2 event: the extractor resolves the
        // one-hot position by name, so an unlisted constant silently collapses into the reference
        // category. QueryType.OTHER is the deliberate exception — it *is* the reference category.
        for (RiskLevel level : RiskLevel.values()) {
            assertThat(ApprovalFeatureVector.FEATURE_SCHEMA_V1)
                    .as("risk_level_%s must have a schema slot", level)
                    .contains("risk_level_" + level.name());
        }
        for (QueryType type : QueryType.values()) {
            if (type == QueryType.OTHER) {
                continue;
            }
            assertThat(ApprovalFeatureVector.FEATURE_SCHEMA_V1)
                    .as("query_type_%s must have a schema slot", type)
                    .contains("query_type_" + type.name());
        }
        assertThat(ApprovalFeatureVector.FEATURE_SCHEMA_V1).doesNotContain("query_type_OTHER");
    }

    @Test
    void constructorRejectsNullValues() {
        assertThatThrownBy(() -> new ApprovalFeatureVector(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("values");
    }

    @Test
    void constructorRejectsTooFewValues() {
        assertThatThrownBy(() -> new ApprovalFeatureVector(new double[20]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected 21")
                .hasMessageContaining("got 20");
    }

    @Test
    void constructorRejectsTooManyValues() {
        assertThatThrownBy(() -> new ApprovalFeatureVector(new double[22]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("got 22");
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void constructorRejectsNonFiniteValuesNamingTheFeature(double bad) {
        var values = zeros();
        values[indexOf("seq_scan")] = bad;

        assertThatThrownBy(() -> new ApprovalFeatureVector(values))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seq_scan");
    }

    @Test
    void constructorCopiesTheInputArray() {
        var values = zeros();
        var vector = new ApprovalFeatureVector(values);

        values[0] = 42.0;

        assertThat(vector.values()[0]).isZero();
    }

    @Test
    void valuesAccessorReturnsADefensiveCopy() {
        var vector = new ApprovalFeatureVector(zeros());

        vector.values()[0] = 42.0;

        assertThat(vector.values()[0]).isZero();
    }

    @Test
    void asSnapshotMapPairsEveryNameWithItsPositionalValueInSchemaOrder() {
        var values = zeros();
        for (int i = 0; i < values.length; i++) {
            values[i] = i;
        }
        // estimate_missing is coerced to a boolean, so exclude it from the numeric comparison.
        values[indexOf("estimate_missing")] = 0.0;

        var snapshot = new ApprovalFeatureVector(values).asSnapshotMap();

        assertThat(snapshot.keySet())
                .containsExactlyElementsOf(ApprovalFeatureVector.FEATURE_SCHEMA_V1);
        assertThat(snapshot).containsEntry("risk_score", 0.0)
                .containsEntry("issue_count", 5.0)
                .containsEntry("datasource_approval_rate", 20.0);
    }

    @Test
    void asSnapshotMapWritesEstimateMissingAsABooleanTrue() {
        var values = zeros();
        values[indexOf("estimate_missing")] = 1.0;

        var snapshot = new ApprovalFeatureVector(values).asSnapshotMap();

        assertThat(snapshot.get("estimate_missing")).isInstanceOf(Boolean.class).isEqualTo(true);
        assertThat(snapshot.keySet())
                .containsExactlyElementsOf(ApprovalFeatureVector.FEATURE_SCHEMA_V1);
    }

    @Test
    void asSnapshotMapWritesEstimateMissingAsABooleanFalse() {
        var snapshot = new ApprovalFeatureVector(zeros()).asSnapshotMap();

        assertThat(snapshot.get("estimate_missing")).isInstanceOf(Boolean.class).isEqualTo(false);
    }

    @Test
    void equalsAndHashCodeCompareArrayContents() {
        var one = new ApprovalFeatureVector(zeros());
        var two = new ApprovalFeatureVector(zeros());

        assertThat(one).isEqualTo(two).hasSameHashCodeAs(two);

        var different = zeros();
        different[0] = 1.0;
        assertThat(one).isNotEqualTo(new ApprovalFeatureVector(different));
    }

    @Test
    void equalsRejectsNullAndOtherTypes() {
        var vector = new ApprovalFeatureVector(zeros());

        assertThat(vector.equals(null)).isFalse();
        assertThat(vector.equals("not a vector")).isFalse();
        assertThat(vector.equals(vector)).isTrue();
    }

    @Test
    void toStringIncludesFeatureNamesAndValues() {
        var values = zeros();
        values[indexOf("off_hours")] = 1.0;

        assertThat(new ApprovalFeatureVector(values).toString())
                .startsWith("ApprovalFeatureVector[")
                .contains("risk_score=0.0")
                .contains("off_hours=1.0")
                .endsWith("]");
    }
}
