package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultApiConnectorPermissionLookupService implements ApiConnectorPermissionLookupService {

    private final EffectiveApiConnectorPermissionResolver permissionResolver;

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
}
