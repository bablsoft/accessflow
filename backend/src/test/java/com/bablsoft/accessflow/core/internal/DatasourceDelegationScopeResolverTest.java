package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DelegationScopeKind;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatasourceDelegationScopeResolverTest {

    @Mock
    private DatasourceRepository datasourceRepository;

    @InjectMocks
    private DatasourceDelegationScopeResolver resolver;

    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    private DatasourceEntity datasource(UUID owningOrgId) {
        var org = new OrganizationEntity();
        org.setId(owningOrgId);
        var datasource = new DatasourceEntity();
        datasource.setId(datasourceId);
        datasource.setName("Production PostgreSQL");
        datasource.setOrganization(org);
        return datasource;
    }

    @Test
    void answersForTheDatasourceScopeKind() {
        assertThat(resolver.supportedKind()).isEqualTo(DelegationScopeKind.DATASOURCE);
    }

    @Test
    void resolvesTheNameOfADatasourceInTheSameOrganization() {
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(datasource(orgId)));

        assertThat(resolver.resolveName(orgId, datasourceId)).contains("Production PostgreSQL");
    }

    @Test
    void refusesToResolveAcrossAnOrganizationBoundary() {
        when(datasourceRepository.findById(datasourceId))
                .thenReturn(Optional.of(datasource(UUID.randomUUID())));

        assertThat(resolver.resolveName(orgId, datasourceId)).isEmpty();
    }

    @Test
    void returnsEmptyForAnUnknownDatasource() {
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.empty());

        assertThat(resolver.resolveName(orgId, datasourceId)).isEmpty();
    }
}
