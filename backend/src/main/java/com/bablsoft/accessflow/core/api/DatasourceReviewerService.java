package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.UUID;

public interface DatasourceReviewerService {

    List<DatasourceReviewerView> listForDatasource(UUID datasourceId, UUID organizationId);

    DatasourceReviewerView add(CreateDatasourceReviewerCommand command);

    void remove(UUID reviewerId, UUID datasourceId, UUID organizationId);
}
