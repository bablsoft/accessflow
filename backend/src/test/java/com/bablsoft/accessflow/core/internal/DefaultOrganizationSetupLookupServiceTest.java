package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultOrganizationSetupLookupServiceTest {

    @Mock DatasourceRepository datasourceRepository;
    @Mock ReviewPlanRepository reviewPlanRepository;
    @InjectMocks DefaultOrganizationSetupLookupService service;

    @Test
    void hasAnyDatasourceDelegatesToRepository() {
        var orgId = UUID.randomUUID();
        when(datasourceRepository.existsByOrganization_Id(orgId)).thenReturn(true);

        assertThat(service.hasAnyDatasource(orgId)).isTrue();
    }

    @Test
    void hasAnyDatasourceReturnsFalseWhenNone() {
        var orgId = UUID.randomUUID();
        when(datasourceRepository.existsByOrganization_Id(orgId)).thenReturn(false);

        assertThat(service.hasAnyDatasource(orgId)).isFalse();
    }

    @Test
    void hasAnyReviewPlanDelegatesToRepository() {
        var orgId = UUID.randomUUID();
        when(reviewPlanRepository.existsByOrganization_Id(orgId)).thenReturn(true);

        assertThat(service.hasAnyReviewPlan(orgId)).isTrue();
    }

    @Test
    void hasAnyReviewPlanReturnsFalseWhenNone() {
        var orgId = UUID.randomUUID();
        when(reviewPlanRepository.existsByOrganization_Id(orgId)).thenReturn(false);

        assertThat(service.hasAnyReviewPlan(orgId)).isFalse();
    }
}
