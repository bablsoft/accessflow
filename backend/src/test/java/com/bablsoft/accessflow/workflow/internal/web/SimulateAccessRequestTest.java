package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.workflow.api.AiOutcome;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SimulateAccessRequestTest {

    @Test
    void mapsEveryFieldOntoTheServiceInput() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();

        var input = new SimulateAccessRequest(userId, datasourceId, "SELECT 1",
                AiOutcome.COMPLETED, RiskLevel.HIGH, 82).toInput();

        assertThat(input.userId()).isEqualTo(userId);
        assertThat(input.datasourceId()).isEqualTo(datasourceId);
        assertThat(input.sql()).isEqualTo("SELECT 1");
        assertThat(input.aiOutcome()).isEqualTo(AiOutcome.COMPLETED);
        assertThat(input.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(input.effectiveRiskScore()).isEqualTo(82);
    }

    @Test
    void anOmittedOutcomeModelsTheNoAiBranchAndAnOmittedScoreIsAbsentNotZero() {
        // -1 is the same "absent" sentinel the live completion event uses; 0 would be a real,
        // and very low, risk score.
        var input = new SimulateAccessRequest(UUID.randomUUID(), UUID.randomUUID(), "SELECT 1",
                null, null, null).toInput();

        assertThat(input.aiOutcome()).isEqualTo(AiOutcome.SKIPPED);
        assertThat(input.effectiveRiskScore()).isEqualTo(-1);
    }
}
