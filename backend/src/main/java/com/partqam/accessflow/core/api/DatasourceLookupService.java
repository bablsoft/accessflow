package com.partqam.accessflow.core.api;

import java.util.Optional;
import java.util.UUID;

public interface DatasourceLookupService {

    Optional<DatasourceConnectionDescriptor> findById(UUID datasourceId);
}
