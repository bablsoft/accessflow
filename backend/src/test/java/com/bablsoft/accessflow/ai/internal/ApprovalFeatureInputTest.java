package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.ApprovalOutcomeSample;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalFeatureInputTest {

    private static final Instant CREATED_AT = Instant.parse("2026-03-04T09:30:00Z");

    @Test
    void fromCopiesEveryFeatureBearingFieldOfTheSample() {
        var sample = new ApprovalOutcomeSample(
                UUID.randomUUID(), QueryType.UPDATE, true, CREATED_AT,
                UUID.randomUUID(), UUID.randomUUID(),
                72, RiskLevel.HIGH, 3, false,
                1_000L, 25L, 8.5, "Seq Scan", false,
                true);

        var input = ApprovalFeatureInput.from(sample);

        assertThat(input.queryType()).isEqualTo(QueryType.UPDATE);
        assertThat(input.transactional()).isTrue();
        assertThat(input.createdAt()).isEqualTo(CREATED_AT);
        assertThat(input.aiRiskScore()).isEqualTo(72);
        assertThat(input.aiRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(input.aiIssueCount()).isEqualTo(3);
        assertThat(input.aiMissing()).isFalse();
        assertThat(input.estimatedRows()).isEqualTo(1_000L);
        assertThat(input.affectedRowCount()).isEqualTo(25L);
        assertThat(input.estimatedCost()).isEqualTo(8.5);
        assertThat(input.scanType()).isEqualTo("Seq Scan");
        assertThat(input.estimateMissing()).isFalse();
    }

    @Test
    void fromPreservesMissingFlagsAndNullPayloads() {
        var sample = new ApprovalOutcomeSample(
                UUID.randomUUID(), QueryType.SELECT, false, CREATED_AT,
                UUID.randomUUID(), UUID.randomUUID(),
                null, null, null, true,
                null, null, null, null, true,
                false);

        var input = ApprovalFeatureInput.from(sample);

        assertThat(input.aiMissing()).isTrue();
        assertThat(input.estimateMissing()).isTrue();
        assertThat(input.aiRiskScore()).isNull();
        assertThat(input.aiRiskLevel()).isNull();
        assertThat(input.aiIssueCount()).isNull();
        assertThat(input.estimatedRows()).isNull();
        assertThat(input.affectedRowCount()).isNull();
        assertThat(input.estimatedCost()).isNull();
        assertThat(input.scanType()).isNull();
    }

    @Test
    void fromRejectsNullSample() {
        assertThatThrownBy(() -> ApprovalFeatureInput.from(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sample");
    }
}
