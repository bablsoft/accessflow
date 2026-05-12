package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.OrganizationSetupLookupService;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultOrganizationSetupLookupService implements OrganizationSetupLookupService {

    private final DatasourceRepository datasourceRepository;
    private final ReviewPlanRepository reviewPlanRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean hasAnyDatasource(UUID organizationId) {
        return datasourceRepository.existsByOrganization_Id(organizationId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasAnyReviewPlan(UUID organizationId) {
        return reviewPlanRepository.existsByOrganization_Id(organizationId);
    }
}
