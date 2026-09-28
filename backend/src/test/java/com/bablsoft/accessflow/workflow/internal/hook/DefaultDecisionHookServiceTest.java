package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.workflow.api.CreateDecisionHookCommand;
import com.bablsoft.accessflow.workflow.api.DecisionHookFailure;
import com.bablsoft.accessflow.workflow.api.DecisionHookNotFoundException;
import com.bablsoft.accessflow.workflow.api.DecisionHookOutcome;
import com.bablsoft.accessflow.workflow.api.DecisionHookScopeConflictException;
import com.bablsoft.accessflow.workflow.api.IllegalDecisionHookException;
import com.bablsoft.accessflow.workflow.api.UpdateDecisionHookCommand;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.DecisionHookEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.DecisionHookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDecisionHookServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final String URL = "https://opa.example.com/v1/data";

    private final UUID organizationId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private DecisionHookRepository repository;
    private CredentialEncryptionService encryption;
    private DatasourceAdminService datasourceAdminService;
    private DecisionHookUrlGuard urlGuard;
    private DecisionHookCircuitBreaker breaker;
    private DecisionHookPayloadFactory payloadFactory;
    private DecisionHookClient client;
    private DefaultDecisionHookService service;

    @BeforeEach
    void setUp() {
        repository = mock(DecisionHookRepository.class);
        encryption = mock(CredentialEncryptionService.class);
        datasourceAdminService = mock(DatasourceAdminService.class);
        urlGuard = mock(DecisionHookUrlGuard.class);
        breaker = mock(DecisionHookCircuitBreaker.class);
        payloadFactory = mock(DecisionHookPayloadFactory.class);
        client = mock(DecisionHookClient.class);
        service = new DefaultDecisionHookService(repository, encryption, datasourceAdminService,
                urlGuard, breaker, payloadFactory, client);
        when(urlGuard.validate(any())).thenAnswer(inv -> URI.create(inv.getArgument(0)));
        when(encryption.encrypt(SECRET)).thenReturn("enc");
        when(encryption.decrypt("enc")).thenReturn(SECRET);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findByOrganizationIdAndDatasourceIdIsNull(organizationId))
                .thenReturn(Optional.empty());
        when(repository.findByOrganizationIdAndDatasourceId(eq(organizationId), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void createEncryptsTheSecretAndNeverExposesIt() {
        var view = service.create(new CreateDecisionHookCommand(organizationId, null, "  OPA  ", URL,
                2000, SECRET, true, true));

        assertThat(view.name()).isEqualTo("OPA");
        assertThat(view.endpointUrl()).isEqualTo(URL);
        assertThat(view.secretConfigured()).isTrue();
        assertThat(view.includeSql()).isTrue();
        verify(repository).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                e -> "enc".equals(e.getSecretEncrypted())));
    }

    @Test
    void createValidatesTheDatasourceInTheCallersOrganization() {
        when(datasourceAdminService.getForAdmin(datasourceId, organizationId))
                .thenThrow(new DatasourceNotFoundException(datasourceId));

        assertThatThrownBy(() -> service.create(new CreateDecisionHookCommand(organizationId,
                datasourceId, "OPA", URL, 2000, SECRET, false, true)))
                .isInstanceOf(DatasourceNotFoundException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void createRefusesAUrlTheGuardRefuses() {
        when(urlGuard.validate("https://10.0.0.1"))
                .thenThrow(new IllegalDecisionHookException("k"));

        assertThatThrownBy(() -> service.create(new CreateDecisionHookCommand(organizationId, null,
                "OPA", "https://10.0.0.1", 2000, SECRET, false, true)))
                .isInstanceOf(IllegalDecisionHookException.class);
    }

    @Test
    void aSecondOrganizationDefaultConflicts() {
        when(repository.findByOrganizationIdAndDatasourceIdIsNull(organizationId))
                .thenReturn(Optional.of(hook(null)));

        assertThatThrownBy(() -> service.create(new CreateDecisionHookCommand(organizationId, null,
                "OPA", URL, 2000, SECRET, false, true)))
                .isInstanceOf(DecisionHookScopeConflictException.class)
                .extracting("datasourceId").isNull();
    }

    @Test
    void aSecondDatasourceHookConflicts() {
        when(repository.findByOrganizationIdAndDatasourceId(organizationId, datasourceId))
                .thenReturn(Optional.of(hook(datasourceId)));

        assertThatThrownBy(() -> service.create(new CreateDecisionHookCommand(organizationId,
                datasourceId, "OPA", URL, 2000, SECRET, false, true)))
                .isInstanceOf(DecisionHookScopeConflictException.class);
    }

    @Test
    void aRacedUniqueViolationBecomesTheSameConflict() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.create(new CreateDecisionHookCommand(organizationId, null,
                "OPA", URL, 2000, SECRET, false, true)))
                .isInstanceOf(DecisionHookScopeConflictException.class);
    }

    @Test
    void updateWithoutASecretKeepsTheStoredOneAndResetsTheBreaker() {
        var hook = hook(null);
        when(repository.findByIdAndOrganizationId(hook.getId(), organizationId))
                .thenReturn(Optional.of(hook));

        var view = service.update(organizationId, hook.getId(), new UpdateDecisionHookCommand(null,
                "Renamed", URL, 500, null, false, false));

        assertThat(view.name()).isEqualTo("Renamed");
        assertThat(view.enabled()).isFalse();
        assertThat(view.timeoutMs()).isEqualTo(500);
        assertThat(hook.getSecretEncrypted()).isEqualTo("old");
        verify(encryption, never()).encrypt(any());
        verify(breaker).reset(hook.getId());
    }

    @Test
    void updateWithASecretRotatesIt() {
        var hook = hook(null);
        when(repository.findByIdAndOrganizationId(hook.getId(), organizationId))
                .thenReturn(Optional.of(hook));

        service.update(organizationId, hook.getId(), new UpdateDecisionHookCommand(null, "OPA", URL,
                2000, SECRET, false, true));

        assertThat(hook.getSecretEncrypted()).isEqualTo("enc");
    }

    @Test
    void movingAHookToAnOccupiedScopeConflicts() {
        var hook = hook(null);
        when(repository.findByIdAndOrganizationId(hook.getId(), organizationId))
                .thenReturn(Optional.of(hook));
        when(repository.findByOrganizationIdAndDatasourceId(organizationId, datasourceId))
                .thenReturn(Optional.of(hook(datasourceId)));

        assertThatThrownBy(() -> service.update(organizationId, hook.getId(),
                new UpdateDecisionHookCommand(datasourceId, "OPA", URL, 2000, null, false, true)))
                .isInstanceOf(DecisionHookScopeConflictException.class);
    }

    @Test
    void keepingTheSameScopeIsNotAConflict() {
        var hook = hook(datasourceId);
        when(repository.findByIdAndOrganizationId(hook.getId(), organizationId))
                .thenReturn(Optional.of(hook));

        service.update(organizationId, hook.getId(),
                new UpdateDecisionHookCommand(datasourceId, "OPA", URL, 2000, null, false, true));

        verify(repository, never()).findByOrganizationIdAndDatasourceId(organizationId, datasourceId);
    }

    @Test
    void aHookOfAnotherOrganizationReadsAsMissing() {
        var id = UUID.randomUUID();
        when(repository.findByIdAndOrganizationId(id, organizationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(organizationId, id))
                .isInstanceOf(DecisionHookNotFoundException.class);
        assertThatThrownBy(() -> service.delete(organizationId, id))
                .isInstanceOf(DecisionHookNotFoundException.class);
    }

    @Test
    void deleteRemovesTheHookAndResetsTheBreaker() {
        var hook = hook(null);
        when(repository.findByIdAndOrganizationId(hook.getId(), organizationId))
                .thenReturn(Optional.of(hook));

        service.delete(organizationId, hook.getId());

        verify(repository).delete(hook);
        verify(breaker).reset(hook.getId());
    }

    @Test
    void listPutsTheOrganizationDefaultFirstThenByName() {
        var zeta = hook(UUID.randomUUID());
        zeta.setName("zeta");
        var alpha = hook(UUID.randomUUID());
        alpha.setName("Alpha");
        var fallback = hook(null);
        fallback.setName("Default");
        when(repository.findAllByOrganizationId(organizationId))
                .thenReturn(List.of(zeta, fallback, alpha));

        assertThat(service.list(organizationId)).extracting("name")
                .containsExactly("Default", "Alpha", "zeta");
    }

    @Test
    void testSendsASignedSyntheticCallAndBypassesTheBreaker() {
        var hook = hook(datasourceId);
        hook.setSecretEncrypted("enc");
        when(repository.findByIdAndOrganizationId(hook.getId(), organizationId))
                .thenReturn(Optional.of(hook));
        when(payloadFactory.forTest(any(), eq(organizationId), eq(datasourceId)))
                .thenReturn(new byte[]{1});
        when(client.call(eq(URL), eq(2000), eq(SECRET), eq("QUERY_DECISION_TEST"), any(), any()))
                .thenReturn(DecisionHookVerdict.failed(DecisionHookFailure.SIGNATURE_MISMATCH, 200,
                        5L));

        var result = service.test(organizationId, hook.getId());

        assertThat(result.outcome()).isEqualTo(DecisionHookOutcome.FAILED);
        assertThat(result.failure()).isEqualTo(DecisionHookFailure.SIGNATURE_MISMATCH);
        verify(breaker, never()).tryAcquire(any());
        verify(breaker, never()).recordFailure(any());
    }

    @Test
    void testReportsAnUndecryptableSecretAsAFailureInsteadOfAnError() {
        var hook = hook(null);
        hook.setSecretEncrypted("broken");
        when(repository.findByIdAndOrganizationId(hook.getId(), organizationId))
                .thenReturn(Optional.of(hook));
        when(encryption.decrypt("broken")).thenThrow(new IllegalStateException("bad key"));

        var result = service.test(organizationId, hook.getId());

        assertThat(result.outcome()).isEqualTo(DecisionHookOutcome.FAILED);
        assertThat(result.failure()).isEqualTo(DecisionHookFailure.TRANSPORT_ERROR);
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    private DecisionHookEntity hook(UUID scope) {
        var hook = new DecisionHookEntity();
        hook.setId(UUID.randomUUID());
        hook.setOrganizationId(organizationId);
        hook.setDatasourceId(scope);
        hook.setName("OPA");
        hook.setEndpointUrl(URL);
        hook.setTimeoutMs(2000);
        hook.setSecretEncrypted("old");
        return hook;
    }
}
