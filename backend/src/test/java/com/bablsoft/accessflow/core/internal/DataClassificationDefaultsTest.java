package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataClassificationDefaultsTest {

    @Test
    void everyClassificationHasADefault() {
        assertThat(DataClassificationDefaults.isComplete()).isTrue();
        for (var classification : DataClassification.values()) {
            assertThat(DataClassificationDefaults.forClassification(classification)).isNotNull();
        }
    }

    @Test
    void pciAndPhiRequireFullMaskingAndTwoApprovals() {
        for (var classification : new DataClassification[]{DataClassification.PCI, DataClassification.PHI}) {
            var def = DataClassificationDefaults.forClassification(classification);
            assertThat(def.maskingStrategy()).isEqualTo(MaskingStrategy.FULL);
            assertThat(def.requiresAiReview()).isTrue();
            assertThat(def.requiresHumanApproval()).isTrue();
            assertThat(def.minApprovals()).isEqualTo(2);
        }
    }

    @Test
    void piiUsesPartialMaskingWithVisibleSuffix() {
        var def = DataClassificationDefaults.forClassification(DataClassification.PII);
        assertThat(def.maskingStrategy()).isEqualTo(MaskingStrategy.PARTIAL);
        assertThat(def.maskingParams()).containsEntry("visible_suffix", "4");
        assertThat(def.minApprovals()).isEqualTo(1);
    }

    @Test
    void sensitiveHashesAndSkipsMandatoryHumanApproval() {
        var def = DataClassificationDefaults.forClassification(DataClassification.SENSITIVE);
        assertThat(def.maskingStrategy()).isEqualTo(MaskingStrategy.HASH);
        assertThat(def.requiresAiReview()).isTrue();
        assertThat(def.requiresHumanApproval()).isFalse();
    }

    @Test
    void financialUsesPartialMasking() {
        var def = DataClassificationDefaults.forClassification(DataClassification.FINANCIAL);
        assertThat(def.maskingStrategy()).isEqualTo(MaskingStrategy.PARTIAL);
        assertThat(def.maskingParams()).containsEntry("visible_suffix", "4");
    }
}
