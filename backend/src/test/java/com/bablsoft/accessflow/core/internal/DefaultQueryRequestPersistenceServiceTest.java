package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SubmitQueryCommand;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultQueryRequestPersistenceServiceTest {

    @Mock QueryRequestRepository queryRequestRepository;
    @Mock DatasourceRepository datasourceRepository;
    @Mock UserRepository userRepository;
    @InjectMocks DefaultQueryRequestPersistenceService service;

    @Test
    void submitInsertsQueryRequestWithDefaults() {
        var datasourceId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var datasource = new DatasourceEntity();
        datasource.setId(datasourceId);
        var user = new UserEntity();
        user.setId(userId);
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(datasource));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(queryRequestRepository.save(any(QueryRequestEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var command = new SubmitQueryCommand(datasourceId, userId, "SELECT 1",
                QueryType.SELECT, false, "ticket-42");

        var id = service.submit(command);

        ArgumentCaptor<QueryRequestEntity> captor = ArgumentCaptor.forClass(QueryRequestEntity.class);
        verify(queryRequestRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getDatasource()).isSameAs(datasource);
        assertThat(saved.getSubmittedBy()).isSameAs(user);
        assertThat(saved.getSqlText()).isEqualTo("SELECT 1");
        assertThat(saved.getQueryType()).isEqualTo(QueryType.SELECT);
        assertThat(saved.isTransactional()).isFalse();
        assertThat(saved.getJustification()).isEqualTo("ticket-42");
        assertThat(saved.getStatus())
                .isEqualTo(com.bablsoft.accessflow.core.api.QueryStatus.PENDING_AI);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void submitThrowsWhenDatasourceMissing() {
        var datasourceId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.empty());

        var command = new SubmitQueryCommand(datasourceId, userId, "SELECT 1",
                QueryType.SELECT, false, null);

        assertThatThrownBy(() -> service.submit(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(datasourceId.toString());
    }

    @Test
    void submitThrowsWhenUserMissing() {
        var datasourceId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var datasource = new DatasourceEntity();
        datasource.setId(datasourceId);
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(datasource));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        var command = new SubmitQueryCommand(datasourceId, userId, "SELECT 1",
                QueryType.SELECT, false, null);

        assertThatThrownBy(() -> service.submit(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(userId.toString());
    }
}
