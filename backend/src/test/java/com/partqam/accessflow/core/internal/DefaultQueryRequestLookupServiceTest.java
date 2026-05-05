package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import com.partqam.accessflow.core.internal.persistence.repo.QueryRequestRepository;
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
class DefaultQueryRequestLookupServiceTest {

    @Mock QueryRequestRepository queryRequestRepository;
    @Mock AiAnalysisRepository aiAnalysisRepository;
    @InjectMocks DefaultQueryRequestLookupService service;

    @Test
    void findByIdMapsFields() {
        var queryId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();

        var organization = new OrganizationEntity();
        organization.setId(organizationId);
        var datasource = new DatasourceEntity();
        datasource.setId(datasourceId);
        datasource.setOrganization(organization);
        var submitter = new UserEntity();
        submitter.setId(userId);
        var entity = new QueryRequestEntity();
        entity.setId(queryId);
        entity.setDatasource(datasource);
        entity.setSubmittedBy(submitter);
        entity.setSqlText("SELECT 1");
        entity.setQueryType(QueryType.SELECT);
        entity.setStatus(QueryStatus.PENDING_AI);

        when(queryRequestRepository.findById(queryId)).thenReturn(Optional.of(entity));

        var result = service.findById(queryId);

        assertThat(result).isPresent();
        var snapshot = result.get();
        assertThat(snapshot.id()).isEqualTo(queryId);
        assertThat(snapshot.datasourceId()).isEqualTo(datasourceId);
        assertThat(snapshot.organizationId()).isEqualTo(organizationId);
        assertThat(snapshot.submittedByUserId()).isEqualTo(userId);
        assertThat(snapshot.sqlText()).isEqualTo("SELECT 1");
        assertThat(snapshot.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(snapshot.status()).isEqualTo(QueryStatus.PENDING_AI);
    }

    @Test
    void findByIdReturnsEmptyWhenMissing() {
        var id = UUID.randomUUID();
        when(queryRequestRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(service.findById(id)).isEmpty();
    }
}
