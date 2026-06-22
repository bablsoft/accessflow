package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DatasourceUserPermissionLookupService {

    Optional<DatasourceUserPermissionView> findFor(UUID userId, UUID datasourceId);

    /**
     * The user's currently-effective break-glass grants — permissions with {@code can_break_glass}
     * set whose {@code expires_at} is null or in the future. Backs the break-glass eligibility check
     * (AF-385).
     */
    List<DatasourceUserPermissionView> findBreakGlassEligible(UUID userId);
}
