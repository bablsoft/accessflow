package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.workflow.api.ConditionContext;
import com.bablsoft.accessflow.workflow.api.DecisionHookFailure;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.DecisionHookEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.DecisionHookRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves and calls the decision hook that applies to a query (#945): the datasource's own hook,
 * else the organization default. A disabled datasource hook means "no hook" for that datasource —
 * it does not fall back to the default, so an admin can switch one datasource off.
 *
 * <p>{@link #live()} calls out, through the circuit breaker; {@link #simulation()} only reports
 * which hook would apply.
 */
@Component
@RequiredArgsConstructor
public class DecisionHookGateway {

    private static final Logger log = LoggerFactory.getLogger(DecisionHookGateway.class);

    private final DecisionHookRepository decisionHookRepository;
    private final CredentialEncryptionService credentialEncryptionService;
    private final DecisionHookPayloadFactory payloadFactory;
    private final DecisionHookClient client;
    private final DecisionHookCircuitBreaker circuitBreaker;

    public DecisionHookInvoker live() {
        return this::consult;
    }

    public DecisionHookInvoker simulation() {
        return (query, context, aiOutcome) ->
                applicable(query.organizationId(), query.datasourceId())
                        .map(hook -> DecisionHookConsultation.simulated(hook.getId(), hook.getName()));
    }

    Optional<DecisionHookEntity> applicable(UUID organizationId, UUID datasourceId) {
        var own = datasourceId == null ? Optional.<DecisionHookEntity>empty()
                : decisionHookRepository.findByOrganizationIdAndDatasourceId(organizationId,
                        datasourceId);
        var hook = own.isPresent() ? own
                : decisionHookRepository.findByOrganizationIdAndDatasourceIdIsNull(organizationId);
        return hook.filter(DecisionHookEntity::isEnabled);
    }

    private Optional<DecisionHookConsultation> consult(QueryRequestSnapshot query,
                                                       ConditionContext context,
                                                       AiOutcome aiOutcome) {
        var found = applicable(query.organizationId(), query.datasourceId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        var hook = found.get();
        if (!circuitBreaker.tryAcquire(hook.getId())) {
            return Optional.of(toConsultation(hook,
                    DecisionHookVerdict.failed(DecisionHookFailure.CIRCUIT_OPEN, null, 0L)));
        }
        var secret = decryptSecret(hook);
        if (secret == null) {
            circuitBreaker.recordFailure(hook.getId());
            return Optional.of(toConsultation(hook,
                    DecisionHookVerdict.failed(DecisionHookFailure.TRANSPORT_ERROR, null, 0L)));
        }
        var requestId = UUID.randomUUID();
        var body = payloadFactory.forQuery(requestId, query, context, aiOutcome, hook.isIncludeSql());
        var verdict = client.call(hook.getEndpointUrl(), hook.getTimeoutMs(), secret,
                DecisionHookPayloadFactory.EVENT_QUERY_DECISION, requestId, body);
        if (verdict.isFailure()) {
            circuitBreaker.recordFailure(hook.getId());
            log.warn("Decision hook {} failed for query {} ({}); sending it to human review",
                    hook.getId(), query.id(), verdict.failure());
        } else {
            circuitBreaker.recordSuccess(hook.getId());
        }
        return Optional.of(toConsultation(hook, verdict));
    }

    /**
     * {@code null} when the stored secret no longer decrypts — typically a rotated
     * {@code ENCRYPTION_KEY}. Failing closed here, rather than throwing, keeps the query from being
     * stranded in {@code PENDING_AI}.
     */
    private String decryptSecret(DecisionHookEntity hook) {
        try {
            return credentialEncryptionService.decrypt(hook.getSecretEncrypted());
        } catch (IllegalStateException ex) {
            log.error("Decision hook {} secret could not be decrypted; failing closed", hook.getId());
            return null;
        }
    }

    private static DecisionHookConsultation toConsultation(DecisionHookEntity hook,
                                                           DecisionHookVerdict verdict) {
        return new DecisionHookConsultation(hook.getId(), hook.getName(), verdict.outcome(),
                verdict.failure(), verdict.requestedApprovals(), verdict.reason(),
                verdict.httpStatus(), verdict.latencyMs());
    }
}
