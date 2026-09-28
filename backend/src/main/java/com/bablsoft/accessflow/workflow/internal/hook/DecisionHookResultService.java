package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.workflow.internal.persistence.entity.DecisionHookResultEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.DecisionHookResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Records every live decision hook consult (#945) — {@code ALLOW} and failures included, which the
 * routing decision alone would not show — and reads it back for the query detail.
 */
@Service
@RequiredArgsConstructor
public class DecisionHookResultService {

    private final DecisionHookResultRepository repository;

    @Transactional
    public void record(UUID queryRequestId, DecisionHookConsultation consultation) {
        var entity = new DecisionHookResultEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequestId(queryRequestId);
        entity.setDecisionHookId(consultation.hookId());
        entity.setDecisionHookName(consultation.hookName());
        entity.setOutcome(consultation.outcome());
        entity.setFailure(consultation.failure());
        entity.setRequestedApprovals(consultation.requestedApprovals());
        entity.setReason(consultation.reason());
        entity.setHttpStatus(consultation.httpStatus());
        entity.setLatencyMs(consultation.latencyMs());
        repository.save(entity);
    }

    @Transactional(readOnly = true)
    public Optional<DecisionHookResultView> findForQuery(UUID queryRequestId) {
        return repository.findByQueryRequestId(queryRequestId)
                .map(e -> new DecisionHookResultView(e.getDecisionHookId(), e.getDecisionHookName(),
                        e.getOutcome(), e.getFailure(), e.getRequestedApprovals(), e.getReason(),
                        e.getHttpStatus(), e.getLatencyMs(), e.getCreatedAt()));
    }
}
