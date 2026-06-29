package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorNotificationLookupService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotificationView;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultApiConnectorNotificationLookupService implements ApiConnectorNotificationLookupService {

    private final ApiConnectorRepository connectorRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<ApiConnectorNotificationView> find(UUID connectorId) {
        return connectorRepository.findById(connectorId)
                .map(c -> new ApiConnectorNotificationView(c.getId(), c.getOrganizationId(), c.getName()));
    }
}
