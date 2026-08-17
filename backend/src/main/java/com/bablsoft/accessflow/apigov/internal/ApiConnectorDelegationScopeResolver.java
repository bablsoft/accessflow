package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.core.api.DelegationScopeKind;
import com.bablsoft.accessflow.core.api.ReviewDelegationScopeResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves {@code API_CONNECTOR}-scoped review delegations (#622).
 *
 * <p>Lives in {@code apigov} because {@code core} owns the delegation table but cannot reach
 * forwards to the connector catalog — {@code apigov} already depends on {@code core}, so the
 * dependency is inverted through {@link ReviewDelegationScopeResolver} rather than reversed.
 */
@Component
@RequiredArgsConstructor
class ApiConnectorDelegationScopeResolver implements ReviewDelegationScopeResolver {

    private final ApiConnectorRepository connectorRepository;

    @Override
    public DelegationScopeKind supportedKind() {
        return DelegationScopeKind.API_CONNECTOR;
    }

    @Override
    public Optional<String> resolveName(UUID organizationId, UUID scopeId) {
        return connectorRepository.findById(scopeId)
                .filter(connector -> organizationId.equals(connector.getOrganizationId()))
                .map(ApiConnectorEntity::getName);
    }
}
