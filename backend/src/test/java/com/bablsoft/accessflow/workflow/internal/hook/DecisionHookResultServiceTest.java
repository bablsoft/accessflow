package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.workflow.api.DecisionHookFailure;
import com.bablsoft.accessflow.workflow.api.DecisionHookOutcome;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.DecisionHookResultEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.DecisionHookResultRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DecisionHookResultServiceTest {

    private final DecisionHookResultRepository repository = mock(DecisionHookResultRepository.class);
    private final DecisionHookResultService service = new DecisionHookResultService(repository);
    private final UUID queryId = UUID.randomUUID();
    private final UUID hookId = UUID.randomUUID();

    @Test
    void recordsEveryFieldOfTheConsultation() {
        service.record(queryId, new DecisionHookConsultation(hookId, "OPA",
                DecisionHookOutcome.FAILED, DecisionHookFailure.NON_2XX, null, null, 503, 40L));

        var saved = ArgumentCaptor.forClass(DecisionHookResultEntity.class);
        verify(repository).save(saved.capture());
        var entity = saved.getValue();
        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getQueryRequestId()).isEqualTo(queryId);
        assertThat(entity.getDecisionHookId()).isEqualTo(hookId);
        assertThat(entity.getDecisionHookName()).isEqualTo("OPA");
        assertThat(entity.getOutcome()).isEqualTo(DecisionHookOutcome.FAILED);
        assertThat(entity.getFailure()).isEqualTo(DecisionHookFailure.NON_2XX);
        assertThat(entity.getHttpStatus()).isEqualTo(503);
        assertThat(entity.getLatencyMs()).isEqualTo(40L);
    }

    @Test
    void readsAResultBackAsAView() {
        var entity = new DecisionHookResultEntity();
        entity.setQueryRequestId(queryId);
        entity.setDecisionHookId(hookId);
        entity.setDecisionHookName("OPA");
        entity.setOutcome(DecisionHookOutcome.ESCALATE);
        entity.setRequestedApprovals(2);
        entity.setReason("pii");
        entity.setHttpStatus(200);
        entity.setLatencyMs(9L);
        when(repository.findByQueryRequestId(queryId)).thenReturn(Optional.of(entity));

        var view = service.findForQuery(queryId).orElseThrow();

        assertThat(view.decisionHookId()).isEqualTo(hookId);
        assertThat(view.outcome()).isEqualTo(DecisionHookOutcome.ESCALATE);
        assertThat(view.requestedApprovals()).isEqualTo(2);
        assertThat(view.reason()).isEqualTo("pii");
        assertThat(view.evaluatedAt()).isEqualTo(entity.getCreatedAt());
    }

    @Test
    void noResultIsEmpty() {
        when(repository.findByQueryRequestId(queryId)).thenReturn(Optional.empty());

        assertThat(service.findForQuery(queryId)).isEmpty();
    }
}
