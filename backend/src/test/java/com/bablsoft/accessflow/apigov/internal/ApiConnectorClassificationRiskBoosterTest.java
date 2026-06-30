package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorClassificationTagEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorClassificationTagRepository;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiConnectorClassificationRiskBoosterTest {

    private final ApiConnectorClassificationTagRepository tagRepository =
            mock(ApiConnectorClassificationTagRepository.class);
    private final ApiConnectorClassificationRiskBooster booster =
            new ApiConnectorClassificationRiskBooster(tagRepository);

    private static AiAnalysisResult result(int score, RiskLevel level) {
        return new AiAnalysisResult(score, level, "summary", List.of(), false, null,
                AiProviderType.ANTHROPIC, "model", 1, 1, List.of());
    }

    private static ApiConnectorClassificationTagEntity tag(DataClassification c, String operationId) {
        var t = new ApiConnectorClassificationTagEntity();
        t.setClassification(c);
        t.setOperationId(operationId);
        return t;
    }

    @Test
    void bumpForTakesStrongestWeight() {
        assertThat(ApiConnectorClassificationRiskBooster.bumpFor(
                List.of(DataClassification.SENSITIVE, DataClassification.PCI))).isEqualTo(30);
        assertThat(ApiConnectorClassificationRiskBooster.bumpFor(List.of())).isZero();
    }

    @Test
    void boostRaisesScoreAndLevelButNeverLowers() {
        var boosted = ApiConnectorClassificationRiskBooster.boost(result(40, RiskLevel.MEDIUM), 30);
        assertThat(boosted.riskScore()).isEqualTo(70);
        assertThat(boosted.riskLevel()).isEqualTo(RiskLevel.HIGH);

        // bump that lands in a lower bucket than the LLM verdict keeps the higher level
        var keepsHigher = ApiConnectorClassificationRiskBooster.boost(result(10, RiskLevel.CRITICAL), 5);
        assertThat(keepsHigher.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void boostClampsScoreToHundred() {
        var boosted = ApiConnectorClassificationRiskBooster.boost(result(90, RiskLevel.HIGH), 30);
        assertThat(boosted.riskScore()).isEqualTo(100);
        assertThat(boosted.riskLevel()).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void zeroBumpReturnsResultUnchanged() {
        var original = result(40, RiskLevel.MEDIUM);
        assertThat(ApiConnectorClassificationRiskBooster.boost(original, 0)).isSameAs(original);
    }

    @Test
    void boostByLookupAppliesMatchingOperationTags() {
        var orgId = UUID.randomUUID();
        var connectorId = UUID.randomUUID();
        when(tagRepository.findAllByOrganizationIdAndConnectorId(orgId, connectorId))
                .thenReturn(List.of(tag(DataClassification.PCI, "charge"),
                        tag(DataClassification.SENSITIVE, "other")));

        var boosted = booster.boost(result(10, RiskLevel.LOW), orgId, connectorId, "charge");

        assertThat(boosted.riskScore()).isEqualTo(40); // PCI +30 (operation matches), SENSITIVE excluded
    }

    @Test
    void boostByLookupIncludesConnectorLevelTags() {
        var orgId = UUID.randomUUID();
        var connectorId = UUID.randomUUID();
        when(tagRepository.findAllByOrganizationIdAndConnectorId(orgId, connectorId))
                .thenReturn(List.of(tag(DataClassification.PII, null)));

        var boosted = booster.boost(result(10, RiskLevel.LOW), orgId, connectorId, "anyOp");

        assertThat(boosted.riskScore()).isEqualTo(25); // PII +15 (connector-level applies to any op)
    }

    @Test
    void boostIsFailSafeOnRepositoryError() {
        var orgId = UUID.randomUUID();
        var connectorId = UUID.randomUUID();
        when(tagRepository.findAllByOrganizationIdAndConnectorId(any(), any()))
                .thenThrow(new RuntimeException("db down"));
        var original = result(10, RiskLevel.LOW);

        assertThat(booster.boost(original, orgId, connectorId, "op")).isSameAs(original);
    }

    @Test
    void nullResultReturnsNull() {
        assertThat(booster.boost(null, UUID.randomUUID(), UUID.randomUUID(), "op")).isNull();
    }
}
