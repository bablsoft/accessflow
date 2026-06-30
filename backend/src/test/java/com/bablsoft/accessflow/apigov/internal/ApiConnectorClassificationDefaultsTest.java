package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiConnectorClassificationDefaultsTest {

    @Test
    void coversEveryClassification() {
        assertThat(ApiConnectorClassificationDefaults.isComplete()).isTrue();
    }

    @Test
    void piiDerivesPartialWithVisibleSuffix() {
        var def = ApiConnectorClassificationDefaults.forClassification(DataClassification.PII);
        assertThat(def.maskingStrategy()).isEqualTo(MaskingStrategy.PARTIAL);
        assertThat(def.maskingParams()).containsEntry("visible_suffix", "4");
        assertThat(def.requiresHumanApproval()).isTrue();
        assertThat(def.minApprovals()).isEqualTo(1);
    }

    @Test
    void pciAndPhiDeriveFullWithTwoApprovals() {
        for (var c : new DataClassification[]{DataClassification.PCI, DataClassification.PHI}) {
            var def = ApiConnectorClassificationDefaults.forClassification(c);
            assertThat(def.maskingStrategy()).isEqualTo(MaskingStrategy.FULL);
            assertThat(def.minApprovals()).isEqualTo(2);
        }
    }

    @Test
    void sensitiveDerivesHashWithoutHumanApproval() {
        var def = ApiConnectorClassificationDefaults.forClassification(DataClassification.SENSITIVE);
        assertThat(def.maskingStrategy()).isEqualTo(MaskingStrategy.HASH);
        assertThat(def.requiresHumanApproval()).isFalse();
    }
}
