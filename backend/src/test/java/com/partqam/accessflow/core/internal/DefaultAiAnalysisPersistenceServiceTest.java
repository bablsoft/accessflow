package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.AiProviderType;
import com.partqam.accessflow.core.api.PersistAiAnalysisCommand;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.core.internal.persistence.entity.AiAnalysisEntity;
import com.partqam.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.partqam.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import com.partqam.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAiAnalysisPersistenceServiceTest {

    @Mock AiAnalysisRepository aiAnalysisRepository;
    @Mock QueryRequestRepository queryRequestRepository;
    @InjectMocks DefaultAiAnalysisPersistenceService service;

    @Test
    void persistInsertsAnalysisAndLinksQueryRequest() {
        var queryRequestId = UUID.randomUUID();
        var queryRequest = new QueryRequestEntity();
        queryRequest.setId(queryRequestId);
        when(queryRequestRepository.findById(queryRequestId)).thenReturn(Optional.of(queryRequest));
        when(aiAnalysisRepository.save(any(AiAnalysisEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var command = new PersistAiAnalysisCommand(
                AiProviderType.ANTHROPIC, "claude-sonnet-4-20250514", 75, RiskLevel.HIGH,
                "summary", "[{\"severity\":\"HIGH\"}]", false, 1000L, 120, 80);

        var id = service.persist(queryRequestId, command);

        ArgumentCaptor<AiAnalysisEntity> captor = ArgumentCaptor.forClass(AiAnalysisEntity.class);
        org.mockito.Mockito.verify(aiAnalysisRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getQueryRequest()).isSameAs(queryRequest);
        assertThat(saved.getAiProvider()).isEqualTo(AiProviderType.ANTHROPIC);
        assertThat(saved.getAiModel()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(saved.getRiskScore()).isEqualTo(75);
        assertThat(saved.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(saved.getSummary()).isEqualTo("summary");
        assertThat(saved.getIssues()).isEqualTo("[{\"severity\":\"HIGH\"}]");
        assertThat(saved.isMissingIndexesDetected()).isFalse();
        assertThat(saved.getAffectsRowEstimate()).isEqualTo(1000L);
        assertThat(saved.getPromptTokens()).isEqualTo(120);
        assertThat(saved.getCompletionTokens()).isEqualTo(80);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(queryRequest.getAiAnalysisId()).isEqualTo(id);
    }

    @Test
    void persistThrowsWhenQueryRequestMissing() {
        var queryRequestId = UUID.randomUUID();
        when(queryRequestRepository.findById(queryRequestId)).thenReturn(Optional.empty());

        var command = new PersistAiAnalysisCommand(
                AiProviderType.ANTHROPIC, "model", 0, RiskLevel.LOW, "ok", "[]", false, null, 0, 0);

        assertThatThrownBy(() -> service.persist(queryRequestId, command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(queryRequestId.toString());
    }
}
