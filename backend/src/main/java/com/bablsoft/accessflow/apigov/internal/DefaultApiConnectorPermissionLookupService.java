package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionLookupService;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUserPermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultApiConnectorPermissionLookupService implements ApiConnectorPermissionLookupService {

    private final EffectiveApiConnectorPermissionResolver permissionResolver;
    private final ApiConnectorUserPermissionRepository userPermissionRepository;

    @Override
    public Optional<ApiConnectorPermissionLookupView> findFor(UUID connectorId, UUID userId) {
        return permissionResolver.resolve(connectorId, userId)
                .map(p -> new ApiConnectorPermissionLookupView(
                        p.connectorId(),
                        p.userId(),
                        p.canRead(),
                        p.canWrite(),
                        p.canBreakGlass(),
                        p.allowedOperations(),
                        p.expiresAt()));
    }

    @Override
    public Optional<ApiConnectorDirectPermissionView> findDirectFor(UUID connectorId, UUID userId) {
        return userPermissionRepository.findByConnectorIdAndUserId(connectorId, userId)
                .map(p -> new ApiConnectorDirectPermissionView(
                        p.getId(),
                        p.getConnectorId(),
                        p.getUserId(),
                        p.getExpiresAt()));
    }
}
