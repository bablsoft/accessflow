package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorLookupService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorRef;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class DefaultApiConnectorLookupService implements ApiConnectorLookupService {

    private final ApiConnectorRepository connectorRepository;

    @Override
    public Optional<ApiConnectorRef> findRef(UUID connectorId) {
        return connectorRepository.findById(connectorId).map(DefaultApiConnectorLookupService::toRef);
    }

    @Override
    public List<ApiConnectorRef> findActiveRefsByOrganization(UUID organizationId) {
        return connectorRepository.findByOrganizationIdAndActiveTrueOrderByNameAsc(organizationId).stream()
                .map(DefaultApiConnectorLookupService::toRef)
                .toList();
    }

    private static ApiConnectorRef toRef(ApiConnectorEntity entity) {
        return new ApiConnectorRef(entity.getId(), entity.getName(), entity.getProtocol(),
                entity.getReviewPlanId());
    }
}
