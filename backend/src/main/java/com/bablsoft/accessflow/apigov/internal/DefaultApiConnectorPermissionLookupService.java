package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionLookupService;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUserPermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultApiConnectorPermissionLookupService implements ApiConnectorPermissionLookupService {

    private final ApiConnectorUserPermissionRepository permissionRepository;

    @Override
    public Optional<ApiConnectorPermissionLookupView> findFor(UUID connectorId, UUID userId) {
        return permissionRepository.findByConnectorIdAndUserId(connectorId, userId)
                .map(p -> new ApiConnectorPermissionLookupView(
                        p.getConnectorId(),
                        p.getUserId(),
                        p.isCanRead(),
                        p.isCanWrite(),
                        p.isCanBreakGlass(),
                        p.getAllowedOperations() == null ? List.of() : List.of(p.getAllowedOperations()),
                        p.getExpiresAt()));
    }
}
