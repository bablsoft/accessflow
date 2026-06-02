package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.workflow.api.RoutingAction;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.RoutingDecisionEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.RoutingPolicyEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.RoutingDecisionRepository;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.RoutingPolicyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutingDecisionServiceTest {

    @Mock RoutingDecisionRepository routingDecisionRepository;
    @Mock RoutingPolicyRepository routingPolicyRepository;
    @Mock QueryRequestStateService queryRequestStateService;

    private RoutingDecisionService service() {
        return new RoutingDecisionService(routingDecisionRepository, routingPolicyRepository,
                queryRequestStateService);
    }

    private final UUID queryId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();

    @Test
    void applyDecisionPersistsRowAndTransitions() {
        var match = new RoutingMatch(policyId, "Payroll", RoutingAction.ESCALATE, 2, "needs care");

        service().applyDecision(queryId, QueryStatus.PENDING_REVIEW, match, 3);

        var captor = ArgumentCaptor.forClass(RoutingDecisionEntity.class);
        verify(routingDecisionRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getQueryRequestId()).isEqualTo(queryId);
        assertThat(saved.getMatchedPolicyId()).isEqualTo(policyId);
        assertThat(saved.getAction()).isEqualTo(RoutingAction.ESCALATE);
        assertThat(saved.getEffectiveMinApprovals()).isEqualTo(3);
        assertThat(saved.getReason()).isEqualTo("needs care");
        verify(queryRequestStateService).transitionTo(queryId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);
    }

    @Test
    void findEffectiveMinApprovalsReturnsValueWhenPresent() {
        var decision = new RoutingDecisionEntity();
        decision.setEffectiveMinApprovals(2);
        when(routingDecisionRepository.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(decision));

        assertThat(service().findEffectiveMinApprovals(queryId)).contains(2);
    }

    @Test
    void findEffectiveMinApprovalsEmptyWhenNullOverride() {
        var decision = new RoutingDecisionEntity();
        decision.setEffectiveMinApprovals(null);
        when(routingDecisionRepository.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(decision));

        assertThat(service().findEffectiveMinApprovals(queryId)).isEmpty();
    }

    @Test
    void findEffectiveMinApprovalsEmptyWhenNoDecision() {
        when(routingDecisionRepository.findByQueryRequestId(queryId)).thenReturn(Optional.empty());

        assertThat(service().findEffectiveMinApprovals(queryId)).isEmpty();
    }

    @Test
    void findMatchedPolicyResolvesPolicyName() {
        var decision = new RoutingDecisionEntity();
        decision.setMatchedPolicyId(policyId);
        decision.setAction(RoutingAction.AUTO_REJECT);
        decision.setReason("blocked");
        var policy = new RoutingPolicyEntity();
        policy.setId(policyId);
        policy.setName("Block payroll deletes");
        when(routingDecisionRepository.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(decision));
        when(routingPolicyRepository.findById(policyId)).thenReturn(Optional.of(policy));

        var view = service().findMatchedPolicy(queryId);

        assertThat(view).isPresent();
        assertThat(view.get().policyName()).isEqualTo("Block payroll deletes");
        assertThat(view.get().action()).isEqualTo(RoutingAction.AUTO_REJECT);
        assertThat(view.get().reason()).isEqualTo("blocked");
    }

    @Test
    void findMatchedPolicyToleratesDeletedPolicy() {
        var decision = new RoutingDecisionEntity();
        decision.setMatchedPolicyId(null); // FK was SET NULL on policy delete
        decision.setAction(RoutingAction.AUTO_APPROVE);
        when(routingDecisionRepository.findByQueryRequestId(queryId))
                .thenReturn(Optional.of(decision));

        var view = service().findMatchedPolicy(queryId);

        assertThat(view).isPresent();
        assertThat(view.get().policyName()).isNull();
    }

    @Test
    void findMatchedPolicyEmptyWhenNoDecision() {
        when(routingDecisionRepository.findByQueryRequestId(queryId)).thenReturn(Optional.empty());

        assertThat(service().findMatchedPolicy(queryId)).isEmpty();
    }
}
