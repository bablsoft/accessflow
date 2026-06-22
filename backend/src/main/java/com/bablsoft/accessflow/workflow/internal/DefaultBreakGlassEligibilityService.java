package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatasourceUserPermissionLookupService;
import com.bablsoft.accessflow.workflow.api.BreakGlassEligibility;
import com.bablsoft.accessflow.workflow.api.BreakGlassEligibilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultBreakGlassEligibilityService implements BreakGlassEligibilityService {

    private final DatasourceUserPermissionLookupService permissionLookupService;

    @Override
    @Transactional(readOnly = true)
    public List<BreakGlassEligibility> findEligible(UUID userId, UUID organizationId) {
        return permissionLookupService.findBreakGlassEligible(userId).stream()
                .map(p -> new BreakGlassEligibility(p.datasourceId(), p.expiresAt()))
                .toList();
    }
}
