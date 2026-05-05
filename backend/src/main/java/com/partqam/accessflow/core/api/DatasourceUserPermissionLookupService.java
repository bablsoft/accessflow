package com.partqam.accessflow.core.api;

import java.util.Optional;
import java.util.UUID;

public interface DatasourceUserPermissionLookupService {

    Optional<DatasourceUserPermissionView> findFor(UUID userId, UUID datasourceId);
}
