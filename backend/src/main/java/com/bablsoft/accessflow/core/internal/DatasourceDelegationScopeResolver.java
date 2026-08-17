package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DelegationScopeKind;
import com.bablsoft.accessflow.core.api.ReviewDelegationScopeResolver;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Resolves {@code DATASOURCE}-scoped review delegations (#622). */
@Component
@RequiredArgsConstructor
class DatasourceDelegationScopeResolver implements ReviewDelegationScopeResolver {

    private final DatasourceRepository datasourceRepository;

    @Override
    public DelegationScopeKind supportedKind() {
        return DelegationScopeKind.DATASOURCE;
    }

    @Override
    public Optional<String> resolveName(UUID organizationId, UUID scopeId) {
        return datasourceRepository.findById(scopeId)
                .filter(datasource -> datasource.getOrganization() != null
                        && organizationId.equals(datasource.getOrganization().getId()))
                .map(DatasourceEntity::getName);
    }
}
