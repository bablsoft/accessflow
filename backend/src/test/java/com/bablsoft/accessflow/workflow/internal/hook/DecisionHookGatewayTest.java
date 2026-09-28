package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.core.api.AiOutcome;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.workflow.api.ConditionContext;
import com.bablsoft.accessflow.workflow.api.DecisionHookFailure;
import com.bablsoft.accessflow.workflow.api.DecisionHookOutcome;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.DecisionHookEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.DecisionHookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DecisionHookGatewayTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private DecisionHookRepository repository;
    private CredentialEncryptionService encryption;
    private DecisionHookPayloadFactory payloadFactory;
    private DecisionHookClient client;
    private DecisionHookCircuitBreaker breaker;
    private DecisionHookGateway gateway;
    private final ConditionContext context = mock(ConditionContext.class);

    @BeforeEach
    void setUp() {
        repository = mock(DecisionHookRepository.class);
        encryption = mock(CredentialEncryptionService.class);
        payloadFactory = mock(DecisionHookPayloadFactory.class);
        client = mock(DecisionHookClient.class);
        breaker = mock(DecisionHookCircuitBreaker.class);
        gateway = new DecisionHookGateway(repository, encryption, payloadFactory, client, breaker);
        when(repository.findByOrganizationIdAndDatasourceId(organizationId, datasourceId))
                .thenReturn(Optional.empty());
        when(repository.findByOrganizationIdAndDatasourceIdIsNull(organizationId))
                .thenReturn(Optional.empty());
        when(encryption.decrypt("enc")).thenReturn("secret");
        when(payloadFactory.forQuery(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new byte[]{1});
        when(breaker.tryAcquire(any())).thenReturn(true);
    }

    @Test
    void noHookMeansNoConsultation() {
        assertThat(gateway.live().consult(query(), context, AiOutcome.COMPLETED)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void theDatasourceHookWinsOverTheOrganizationDefault() {
        var own = hook(true);
        when(repository.findByOrganizationIdAndDatasourceId(organizationId, datasourceId))
                .thenReturn(Optional.of(own));
        when(repository.findByOrganizationIdAndDatasourceIdIsNull(organizationId))
                .thenReturn(Optional.of(hook(true)));

        assertThat(gateway.applicable(organizationId, datasourceId)).contains(own);
    }

    @Test
    void aDisabledDatasourceHookDoesNotFallBackToTheDefault() {
        when(repository.findByOrganizationIdAndDatasourceId(organizationId, datasourceId))
                .thenReturn(Optional.of(hook(false)));
        when(repository.findByOrganizationIdAndDatasourceIdIsNull(organizationId))
                .thenReturn(Optional.of(hook(true)));

        assertThat(gateway.applicable(organizationId, datasourceId)).isEmpty();
    }

    @Test
    void theOrganizationDefaultAppliesWithoutADatasourceHook() {
        var fallback = hook(true);
        when(repository.findByOrganizationIdAndDatasourceIdIsNull(organizationId))
                .thenReturn(Optional.of(fallback));

        assertThat(gateway.applicable(organizationId, datasourceId)).contains(fallback);
        assertThat(gateway.applicable(organizationId, null)).contains(fallback);
    }

    @Test
    void aSuccessfulCallIsReturnedAndClosesTheBreaker() {
        var hook = givenDefault();
        when(client.call(eq("https://opa.example.com"), eq(2000), eq("secret"),
                eq("QUERY_DECISION"), any(), any()))
                .thenReturn(new DecisionHookVerdict(DecisionHookOutcome.ESCALATE, null, 2, "r", 200,
                        15L));

        var consultation = gateway.live().consult(query(), context, AiOutcome.COMPLETED)
                .orElseThrow();

        assertThat(consultation.hookId()).isEqualTo(hook.getId());
        assertThat(consultation.hookName()).isEqualTo("OPA");
        assertThat(consultation.outcome()).isEqualTo(DecisionHookOutcome.ESCALATE);
        assertThat(consultation.requestedApprovals()).isEqualTo(2);
        verify(breaker).recordSuccess(hook.getId());
    }

    @Test
    void aFailedCallFeedsTheBreaker() {
        var hook = givenDefault();
        when(client.call(anyString(), anyInt(), anyString(), anyString(), any(), any()))
                .thenReturn(DecisionHookVerdict.failed(DecisionHookFailure.TIMEOUT, null, 2000L));

        var consultation = gateway.live().consult(query(), context, AiOutcome.COMPLETED)
                .orElseThrow();

        assertThat(consultation.isFailure()).isTrue();
        verify(breaker).recordFailure(hook.getId());
    }

    @Test
    void anOpenCircuitFailsClosedWithoutACall() {
        givenDefault();
        when(breaker.tryAcquire(any())).thenReturn(false);

        var consultation = gateway.live().consult(query(), context, AiOutcome.COMPLETED)
                .orElseThrow();

        assertThat(consultation.failure()).isEqualTo(DecisionHookFailure.CIRCUIT_OPEN);
        verifyNoInteractions(client);
    }

    @Test
    void anUndecryptableSecretFailsClosedWithoutACall() {
        var hook = givenDefault();
        when(encryption.decrypt("enc")).thenThrow(new IllegalStateException("bad key"));

        var consultation = gateway.live().consult(query(), context, AiOutcome.COMPLETED)
                .orElseThrow();

        assertThat(consultation.failure()).isEqualTo(DecisionHookFailure.TRANSPORT_ERROR);
        verify(breaker).recordFailure(hook.getId());
        verifyNoInteractions(client);
    }

    @Test
    void anUnexpectedErrorFailsClosedAndReleasesTheBreaker() {
        var hook = givenDefault();
        when(payloadFactory.forQuery(any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("db blip"));

        var consultation = gateway.live().consult(query(), context, AiOutcome.COMPLETED)
                .orElseThrow();

        assertThat(consultation.failure()).isEqualTo(DecisionHookFailure.TRANSPORT_ERROR);
        verify(breaker).recordFailure(hook.getId());
    }

    @Test
    void appliesToReflectsTheResolvedHook() {
        assertThat(gateway.appliesTo(organizationId, datasourceId)).isFalse();
        givenDefault();
        assertThat(gateway.appliesTo(organizationId, datasourceId)).isTrue();
    }

    @Test
    void theSimulationInvokerNamesTheHookButNeverCalls() {
        var hook = givenDefault();

        var consultation = gateway.simulation().consult(query(), context, AiOutcome.COMPLETED)
                .orElseThrow();

        assertThat(consultation.isSimulated()).isTrue();
        assertThat(consultation.hookId()).isEqualTo(hook.getId());
        verifyNoInteractions(client);
        verify(breaker, never()).tryAcquire(any());
    }

    @Test
    void theSimulationInvokerIsEmptyWithoutAHook() {
        assertThat(gateway.simulation().consult(query(), context, AiOutcome.COMPLETED)).isEmpty();
    }

    private DecisionHookEntity givenDefault() {
        var hook = hook(true);
        when(repository.findByOrganizationIdAndDatasourceIdIsNull(organizationId))
                .thenReturn(Optional.of(hook));
        return hook;
    }

    private DecisionHookEntity hook(boolean enabled) {
        var hook = new DecisionHookEntity();
        hook.setId(UUID.randomUUID());
        hook.setOrganizationId(organizationId);
        hook.setName("OPA");
        hook.setEndpointUrl("https://opa.example.com");
        hook.setTimeoutMs(2000);
        hook.setSecretEncrypted("enc");
        hook.setEnabled(enabled);
        return hook;
    }

    private QueryRequestSnapshot query() {
        return new QueryRequestSnapshot(UUID.randomUUID(), datasourceId, organizationId,
                UUID.randomUUID(), "SELECT 1", QueryType.SELECT, false, QueryStatus.PENDING_AI, null,
                null, null, false);
    }
}
