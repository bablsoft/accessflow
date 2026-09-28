package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.workflow.api.CreateDecisionHookCommand;
import com.bablsoft.accessflow.workflow.api.DecisionHookNotFoundException;
import com.bablsoft.accessflow.workflow.api.DecisionHookScopeConflictException;
import com.bablsoft.accessflow.workflow.api.DecisionHookService;
import com.bablsoft.accessflow.workflow.api.DecisionHookTestResult;
import com.bablsoft.accessflow.workflow.api.DecisionHookView;
import com.bablsoft.accessflow.workflow.api.UpdateDecisionHookCommand;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.DecisionHookEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.DecisionHookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Admin CRUD for decision hooks (#945). The secret is encrypted before it is stored and never read
 * back into a view; the endpoint URL passes the SSRF guard before it is stored.
 */
@Service
@RequiredArgsConstructor
class DefaultDecisionHookService implements DecisionHookService {

    private final DecisionHookRepository repository;
    private final CredentialEncryptionService credentialEncryptionService;
    private final DatasourceAdminService datasourceAdminService;
    private final DecisionHookUrlGuard urlGuard;
    private final DecisionHookCircuitBreaker circuitBreaker;
    private final DecisionHookPayloadFactory payloadFactory;
    private final DecisionHookClient client;

    @Override
    @Transactional(readOnly = true)
    public List<DecisionHookView> list(UUID organizationId) {
        return repository.findAllByOrganizationId(organizationId).stream()
                .sorted(Comparator.comparing((DecisionHookEntity h) -> h.getDatasourceId() != null)
                        .thenComparing(DecisionHookEntity::getName, String.CASE_INSENSITIVE_ORDER))
                .map(DefaultDecisionHookService::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DecisionHookView get(UUID organizationId, UUID id) {
        return toView(find(organizationId, id));
    }

    @Override
    @Transactional
    public DecisionHookView create(CreateDecisionHookCommand command) {
        var organizationId = command.organizationId();
        validateDatasource(organizationId, command.datasourceId());
        var url = urlGuard.validate(command.endpointUrl()).toString();
        ensureScopeFree(organizationId, command.datasourceId(), null);
        var entity = new DecisionHookEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setDatasourceId(command.datasourceId());
        entity.setName(command.name().strip());
        entity.setEndpointUrl(url);
        entity.setTimeoutMs(command.timeoutMs());
        entity.setSecretEncrypted(credentialEncryptionService.encrypt(command.secret()));
        entity.setIncludeSql(command.includeSql());
        entity.setEnabled(command.enabled());
        return toView(save(entity));
    }

    @Override
    @Transactional
    public DecisionHookView update(UUID organizationId, UUID id, UpdateDecisionHookCommand command) {
        var entity = find(organizationId, id);
        validateDatasource(organizationId, command.datasourceId());
        var url = urlGuard.validate(command.endpointUrl()).toString();
        if (!Objects.equals(entity.getDatasourceId(), command.datasourceId())) {
            ensureScopeFree(organizationId, command.datasourceId(), id);
        }
        entity.setDatasourceId(command.datasourceId());
        entity.setName(command.name().strip());
        entity.setEndpointUrl(url);
        entity.setTimeoutMs(command.timeoutMs());
        if (command.secret() != null) {
            entity.setSecretEncrypted(credentialEncryptionService.encrypt(command.secret()));
        }
        entity.setIncludeSql(command.includeSql());
        entity.setEnabled(command.enabled());
        var saved = save(entity);
        circuitBreaker.reset(id);
        return toView(saved);
    }

    @Override
    @Transactional
    public void delete(UUID organizationId, UUID id) {
        repository.delete(find(organizationId, id));
        circuitBreaker.reset(id);
    }

    @Override
    @Transactional(readOnly = true)
    public DecisionHookTestResult test(UUID organizationId, UUID id) {
        var hook = find(organizationId, id);
        var requestId = UUID.randomUUID();
        var body = payloadFactory.forTest(requestId, organizationId, hook.getDatasourceId());
        return client.call(hook.getEndpointUrl(), hook.getTimeoutMs(),
                credentialEncryptionService.decrypt(hook.getSecretEncrypted()),
                DecisionHookPayloadFactory.EVENT_TEST, requestId, body).toTestResult();
    }

    private DecisionHookEntity find(UUID organizationId, UUID id) {
        return repository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new DecisionHookNotFoundException(id));
    }

    /** 404-never-403: a datasource of another organization reads as missing. */
    private void validateDatasource(UUID organizationId, UUID datasourceId) {
        if (datasourceId != null) {
            datasourceAdminService.getForAdmin(datasourceId, organizationId);
        }
    }

    private void ensureScopeFree(UUID organizationId, UUID datasourceId, UUID exceptId) {
        var existing = datasourceId == null
                ? repository.findByOrganizationIdAndDatasourceIdIsNull(organizationId)
                : repository.findByOrganizationIdAndDatasourceId(organizationId, datasourceId);
        if (existing.filter(h -> !h.getId().equals(exceptId)).isPresent()) {
            throw new DecisionHookScopeConflictException(datasourceId);
        }
    }

    /** A concurrent create for the same scope loses on the partial unique index; same 409. */
    private DecisionHookEntity save(DecisionHookEntity entity) {
        try {
            return repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            throw new DecisionHookScopeConflictException(entity.getDatasourceId());
        }
    }

    private static DecisionHookView toView(DecisionHookEntity e) {
        return new DecisionHookView(e.getId(), e.getOrganizationId(), e.getDatasourceId(),
                e.getName(), e.getEndpointUrl(), e.getTimeoutMs(), e.isIncludeSql(), e.isEnabled(),
                e.getSecretEncrypted() != null, e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
