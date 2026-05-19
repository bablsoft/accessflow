package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.internal.persistence.entity.AiAnalysisEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAiAnalysisLookupServiceTest {

    @Mock AiAnalysisRepository aiAnalysisRepository;
    @InjectMocks DefaultAiAnalysisLookupService service;

    @Test
    void findByQueryRequestIdReturnsEmptyWhenAbsent() {
        var qid = UUID.randomUUID();
        when(aiAnalysisRepository.findByQueryRequest_Id(qid)).thenReturn(Optional.empty());

        assertThat(service.findByQueryRequestId(qid)).isEmpty();
    }

    @Test
    void findByQueryRequestIdMapsAllFieldsForSuccessfulAnalysis() {
        var qid = UUID.randomUUID();
        var entity = analysisEntity(qid, RiskLevel.MEDIUM, 55, "looks ok", false, null);
        when(aiAnalysisRepository.findByQueryRequest_Id(qid)).thenReturn(Optional.of(entity));

        var view = service.findByQueryRequestId(qid).orElseThrow();

        assertThat(view.queryRequestId()).isEqualTo(qid);
        assertThat(view.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(view.riskScore()).isEqualTo(55);
        assertThat(view.summary()).isEqualTo("looks ok");
        assertThat(view.failed()).isFalse();
        assertThat(view.errorMessage()).isNull();
    }

    @Test
    void findByQueryRequestIdCarriesFailureFlagAndReason() {
        var qid = UUID.randomUUID();
        var entity = analysisEntity(qid, RiskLevel.CRITICAL, 100,
                "AI analysis failed: provider unavailable", true, "provider unavailable");
        when(aiAnalysisRepository.findByQueryRequest_Id(qid)).thenReturn(Optional.of(entity));

        var view = service.findByQueryRequestId(qid).orElseThrow();

        assertThat(view.failed()).isTrue();
        assertThat(view.errorMessage()).isEqualTo("provider unavailable");
    }

    private static AiAnalysisEntity analysisEntity(UUID queryRequestId, RiskLevel riskLevel,
                                                   int riskScore, String summary,
                                                   boolean failed, String errorMessage) {
        var query = new QueryRequestEntity();
        query.setId(queryRequestId);
        var entity = new AiAnalysisEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequest(query);
        entity.setRiskLevel(riskLevel);
        entity.setRiskScore(riskScore);
        entity.setSummary(summary);
        entity.setFailed(failed);
        entity.setErrorMessage(errorMessage);
        return entity;
    }
}
