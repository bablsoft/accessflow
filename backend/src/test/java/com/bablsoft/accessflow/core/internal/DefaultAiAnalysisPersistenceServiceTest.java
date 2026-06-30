package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.PersistAiAnalysisCommand;
import com.bablsoft.accessflow.core.api.PersistAiModelResultCommand;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.internal.persistence.entity.AiAnalysisEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.AiAnalysisModelResultEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.AiAnalysisModelResultRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAiAnalysisPersistenceServiceTest {

    @Mock AiAnalysisRepository aiAnalysisRepository;
    @Mock AiAnalysisModelResultRepository modelResultRepository;
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
                "summary", "[{\"severity\":\"HIGH\"}]", "[{\"type\":\"INDEX\"}]", false, 1000L, 120, 80,
                false, null);

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
        assertThat(saved.getOptimizations()).isEqualTo("[{\"type\":\"INDEX\"}]");
        assertThat(saved.isMissingIndexesDetected()).isFalse();
        assertThat(saved.getAffectsRowEstimate()).isEqualTo(1000L);
        assertThat(saved.getPromptTokens()).isEqualTo(120);
        assertThat(saved.getCompletionTokens()).isEqualTo(80);
        assertThat(saved.isFailed()).isFalse();
        assertThat(saved.getErrorMessage()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(queryRequest.getAiAnalysisId()).isEqualTo(id);
    }

    @Test
    void persistWritesPerModelBreakdownRows() {
        var queryRequestId = UUID.randomUUID();
        var queryRequest = new QueryRequestEntity();
        queryRequest.setId(queryRequestId);
        when(queryRequestRepository.findById(queryRequestId)).thenReturn(Optional.of(queryRequest));
        when(aiAnalysisRepository.save(any(AiAnalysisEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var command = new PersistAiAnalysisCommand(
                AiProviderType.ANTHROPIC, "claude", 70, RiskLevel.HIGH, "s", "[]", "[]", false, null,
                100, 30, false, null,
                java.util.List.of(
                        new PersistAiModelResultCommand(AiProviderType.ANTHROPIC, "claude", 70,
                                RiskLevel.HIGH, 1.0, 60, 20, 800L, false, null),
                        new PersistAiModelResultCommand(AiProviderType.OLLAMA, "llama", null, null,
                                2.0, 40, 10, 150L, true, "down")));

        var id = service.persist(queryRequestId, command);

        ArgumentCaptor<AiAnalysisModelResultEntity> captor =
                ArgumentCaptor.forClass(AiAnalysisModelResultEntity.class);
        org.mockito.Mockito.verify(modelResultRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        var rows = captor.getAllValues();
        assertThat(rows).allMatch(r -> r.getAiAnalysisId().equals(id));
        assertThat(rows.get(0).getAiModel()).isEqualTo("claude");
        assertThat(rows.get(0).getLatencyMs()).isEqualTo(800L);
        assertThat(rows.get(0).isFailed()).isFalse();
        assertThat(rows.get(1).getAiModel()).isEqualTo("llama");
        assertThat(rows.get(1).isFailed()).isTrue();
        assertThat(rows.get(1).getErrorMessage()).isEqualTo("down");
        assertThat(rows.get(1).getRiskScore()).isNull();
    }

    @Test
    void persistRecordsFailureFlagAndReason() {
        var queryRequestId = UUID.randomUUID();
        var queryRequest = new QueryRequestEntity();
        queryRequest.setId(queryRequestId);
        when(queryRequestRepository.findById(queryRequestId)).thenReturn(Optional.of(queryRequest));
        when(aiAnalysisRepository.save(any(AiAnalysisEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        var command = new PersistAiAnalysisCommand(
                AiProviderType.ANTHROPIC, "unknown", 100, RiskLevel.CRITICAL,
                "AI analysis failed: provider unavailable", "[]", "[]", false, null, 0, 0,
                true, "provider unavailable");

        service.persist(queryRequestId, command);

        ArgumentCaptor<AiAnalysisEntity> captor = ArgumentCaptor.forClass(AiAnalysisEntity.class);
        org.mockito.Mockito.verify(aiAnalysisRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.isFailed()).isTrue();
        assertThat(saved.getErrorMessage()).isEqualTo("provider unavailable");
    }

    @Test
    void deleteForQueryClearsLinkAndRemovesRow() {
        var queryRequestId = UUID.randomUUID();
        var analysisId = UUID.randomUUID();
        var queryRequest = new QueryRequestEntity();
        queryRequest.setId(queryRequestId);
        queryRequest.setAiAnalysisId(analysisId);
        when(queryRequestRepository.findById(queryRequestId)).thenReturn(Optional.of(queryRequest));

        service.deleteForQuery(queryRequestId);

        assertThat(queryRequest.getAiAnalysisId()).isNull();
        org.mockito.Mockito.verify(aiAnalysisRepository).deleteById(analysisId);
    }

    @Test
    void deleteForQueryIsNoOpWhenNoAnalysisLinked() {
        var queryRequestId = UUID.randomUUID();
        var queryRequest = new QueryRequestEntity();
        queryRequest.setId(queryRequestId);
        // aiAnalysisId stays null
        when(queryRequestRepository.findById(queryRequestId)).thenReturn(Optional.of(queryRequest));

        service.deleteForQuery(queryRequestId);

        assertThat(queryRequest.getAiAnalysisId()).isNull();
        org.mockito.Mockito.verify(aiAnalysisRepository, org.mockito.Mockito.never())
                .deleteById(any(UUID.class));
    }

    @Test
    void deleteForQueryThrowsWhenQueryMissing() {
        var queryRequestId = UUID.randomUUID();
        when(queryRequestRepository.findById(queryRequestId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteForQuery(queryRequestId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(queryRequestId.toString());
    }

    @Test
    void persistThrowsWhenQueryRequestMissing() {
        var queryRequestId = UUID.randomUUID();
        when(queryRequestRepository.findById(queryRequestId)).thenReturn(Optional.empty());

        var command = new PersistAiAnalysisCommand(
                AiProviderType.ANTHROPIC, "model", 0, RiskLevel.LOW, "ok", "[]", "[]", false, null, 0, 0,
                false, null);

        assertThatThrownBy(() -> service.persist(queryRequestId, command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(queryRequestId.toString());
    }

    @Test
    void persistForGroupItemKeysAnalysisToTheMemberAndReturnsId() {
        var itemId = UUID.randomUUID();
        when(aiAnalysisRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var command = new PersistAiAnalysisCommand(
                AiProviderType.OPENAI, "gpt", 42, RiskLevel.MEDIUM, "ok", "[]", "[]", false, null, 3, 4,
                false, null);

        var id = service.persistForGroupItem(itemId, command);

        var captor = ArgumentCaptor.forClass(AiAnalysisEntity.class);
        verify(aiAnalysisRepository).save(captor.capture());
        assertThat(captor.getValue().getRequestGroupItemId()).isEqualTo(itemId);
        assertThat(captor.getValue().getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(id).isEqualTo(captor.getValue().getId());
    }
}
