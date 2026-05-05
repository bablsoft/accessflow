package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.SslMode;
import com.partqam.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.partqam.accessflow.core.internal.persistence.repo.DatasourceRepository;
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
class DefaultDatasourceLookupServiceTest {

    @Mock DatasourceRepository datasourceRepository;
    @InjectMocks DefaultDatasourceLookupService service;

    private final UUID id = UUID.randomUUID();

    @Test
    void findByIdMapsAllConnectionFields() {
        var entity = new DatasourceEntity();
        entity.setId(id);
        entity.setDbType(DbType.POSTGRESQL);
        entity.setHost("h");
        entity.setPort(5432);
        entity.setDatabaseName("appdb");
        entity.setUsername("svc");
        entity.setPasswordEncrypted("ENC(secret)");
        entity.setSslMode(SslMode.REQUIRE);
        entity.setConnectionPoolSize(15);
        entity.setActive(true);
        when(datasourceRepository.findById(id)).thenReturn(Optional.of(entity));

        var result = service.findById(id);

        assertThat(result).isPresent();
        var descriptor = result.get();
        assertThat(descriptor.id()).isEqualTo(id);
        assertThat(descriptor.dbType()).isEqualTo(DbType.POSTGRESQL);
        assertThat(descriptor.host()).isEqualTo("h");
        assertThat(descriptor.port()).isEqualTo(5432);
        assertThat(descriptor.databaseName()).isEqualTo("appdb");
        assertThat(descriptor.username()).isEqualTo("svc");
        assertThat(descriptor.passwordEncrypted()).isEqualTo("ENC(secret)");
        assertThat(descriptor.sslMode()).isEqualTo(SslMode.REQUIRE);
        assertThat(descriptor.connectionPoolSize()).isEqualTo(15);
        assertThat(descriptor.active()).isTrue();
    }

    @Test
    void findByIdReturnsEmptyWhenMissing() {
        when(datasourceRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(service.findById(id)).isEmpty();
    }
}
