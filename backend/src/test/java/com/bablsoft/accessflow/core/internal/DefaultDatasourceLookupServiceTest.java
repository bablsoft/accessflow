package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
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
class DefaultDatasourceLookupServiceTest {

    @Mock DatasourceRepository datasourceRepository;
    @InjectMocks DefaultDatasourceLookupService service;

    private final UUID id = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();

    @Test
    void findByIdMapsAllConnectionFields() {
        var org = new OrganizationEntity();
        org.setId(organizationId);
        var entity = new DatasourceEntity();
        entity.setId(id);
        entity.setOrganization(org);
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
        assertThat(descriptor.organizationId()).isEqualTo(organizationId);
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

    @Test
    void findRefReturnsIdAndName() {
        var org = new OrganizationEntity();
        org.setId(organizationId);
        var entity = new DatasourceEntity();
        entity.setId(id);
        entity.setOrganization(org);
        entity.setName("analytics");
        when(datasourceRepository.findById(id)).thenReturn(Optional.of(entity));

        var ref = service.findRef(id).orElseThrow();
        assertThat(ref.id()).isEqualTo(id);
        assertThat(ref.name()).isEqualTo("analytics");
    }

    @Test
    void findRefReturnsEmptyForNullId() {
        assertThat(service.findRef(null)).isEmpty();
    }

    @Test
    void findActiveRefsByOrganizationSortsByNameCaseInsensitive() {
        var org = new OrganizationEntity();
        org.setId(organizationId);
        var a = new DatasourceEntity();
        a.setId(UUID.randomUUID());
        a.setOrganization(org);
        a.setName("Zeta");
        var b = new DatasourceEntity();
        b.setId(UUID.randomUUID());
        b.setOrganization(org);
        b.setName("alpha");
        when(datasourceRepository.findAllByOrganization_IdAndActiveTrue(organizationId))
                .thenReturn(java.util.List.of(a, b));

        var refs = service.findActiveRefsByOrganization(organizationId);

        assertThat(refs).extracting(com.bablsoft.accessflow.core.api.DatasourceRef::name)
                .containsExactly("alpha", "Zeta");
    }

    @Test
    void findActiveRefsByOrganizationReturnsEmptyForNull() {
        assertThat(service.findActiveRefsByOrganization(null)).isEmpty();
    }

    @Test
    void findByIdMapsCustomDriverIdAndJdbcUrlOverride() {
        var org = new OrganizationEntity();
        org.setId(organizationId);
        var customDriver = new com.bablsoft.accessflow.core.internal.persistence.entity
                .CustomJdbcDriverEntity();
        var customDriverId = UUID.randomUUID();
        customDriver.setId(customDriverId);
        customDriver.setOrganization(org);
        customDriver.setTargetDbType(DbType.CUSTOM);
        customDriver.setDriverClass("com.acme.JdbcDriver");
        customDriver.setVendorName("Acme");
        customDriver.setJarFilename("acme.jar");
        customDriver.setJarSha256("a".repeat(64));
        customDriver.setJarSizeBytes(1024);
        customDriver.setStoragePath("custom/x.jar");

        var entity = new DatasourceEntity();
        entity.setId(id);
        entity.setOrganization(org);
        entity.setDbType(DbType.CUSTOM);
        entity.setUsername("svc");
        entity.setPasswordEncrypted("ENC(secret)");
        entity.setSslMode(SslMode.DISABLE);
        entity.setConnectionPoolSize(5);
        entity.setActive(true);
        entity.setCustomDriver(customDriver);
        entity.setJdbcUrlOverride("jdbc:vendor://host/db");
        when(datasourceRepository.findById(id)).thenReturn(Optional.of(entity));

        var descriptor = service.findById(id).orElseThrow();

        assertThat(descriptor.customDriverId()).isEqualTo(customDriverId);
        assertThat(descriptor.jdbcUrlOverride()).isEqualTo("jdbc:vendor://host/db");
        assertThat(descriptor.host()).isNull();
        assertThat(descriptor.port()).isNull();
        assertThat(descriptor.databaseName()).isNull();
    }

    @Test
    void findRefsByAiConfigIdReturnsEmptyForNull() {
        assertThat(service.findRefsByAiConfigId(null)).isEmpty();
    }

    @Test
    void findRefsByAiConfigIdMapsRefs() {
        var aiConfigId = UUID.randomUUID();
        var org = new OrganizationEntity();
        org.setId(organizationId);
        var entity = new DatasourceEntity();
        entity.setId(id);
        entity.setOrganization(org);
        entity.setName("metrics");
        when(datasourceRepository.findAllByAiConfigId(aiConfigId))
                .thenReturn(java.util.List.of(entity));

        var refs = service.findRefsByAiConfigId(aiConfigId);

        assertThat(refs).singleElement()
                .satisfies(r -> assertThat(r.name()).isEqualTo("metrics"));
    }

    @Test
    void countsByAiConfigIdsReturnsEmptyForEmptyInput() {
        assertThat(service.countsByAiConfigIds(java.util.Set.of())).isEmpty();
        assertThat(service.countsByAiConfigIds(null)).isEmpty();
    }

    @Test
    void countsByAiConfigIdsAggregatesRows() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        when(datasourceRepository.countByAiConfigIdIn(java.util.Set.of(a, b)))
                .thenReturn(java.util.List.of(new Object[]{a, 3L}, new Object[]{b, 1L}));

        var counts = service.countsByAiConfigIds(java.util.Set.of(a, b));

        assertThat(counts).containsEntry(a, 3).containsEntry(b, 1);
    }

    @Test
    void findActiveAiAnalysisAiConfigIdsReturnsEmptyForNull() {
        assertThat(service.findActiveAiAnalysisAiConfigIdsByOrganization(null)).isEmpty();
    }

    @Test
    void findActiveAiAnalysisAiConfigIdsDelegatesToRepository() {
        var configId = UUID.randomUUID();
        when(datasourceRepository.findActiveAiAnalysisAiConfigIdsByOrganization(organizationId))
                .thenReturn(java.util.List.of(configId));

        assertThat(service.findActiveAiAnalysisAiConfigIdsByOrganization(organizationId))
                .containsExactly(configId);
    }
}
