package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateDatasourceCommand;
import com.bablsoft.accessflow.core.api.CreatePermissionCommand;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DatasourceNameAlreadyExistsException;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourcePermissionAlreadyExistsException;
import com.bablsoft.accessflow.core.api.DatasourcePermissionNotFoundException;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverResolutionException;
import com.bablsoft.accessflow.core.api.IllegalDatasourcePermissionException;
import com.bablsoft.accessflow.core.api.JdbcCoordinatesFactory;
import com.bablsoft.accessflow.core.api.MissingAiConfigForDatasourceException;
import com.bablsoft.accessflow.core.api.ReplicaEndpointInput;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UpdateDatasourceCommand;
import com.bablsoft.accessflow.core.events.DatasourceCacheConfigChangedEvent;
import com.bablsoft.accessflow.core.events.DatasourceConfigChangedEvent;
import com.bablsoft.accessflow.core.events.DatasourceDeactivatedEvent;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceReadReplicaEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatasourceAdminServiceImplTest {

    @Mock DatasourceRepository datasourceRepository;
    @Mock DatasourceUserPermissionRepository permissionRepository;
    @Mock com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceGroupPermissionRepository
            groupPermissionRepository;
    @Mock com.bablsoft.accessflow.core.api.UserGroupService userGroupService;
    @Mock com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupRepository userGroupRepository;
    @Mock com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupMembershipRepository
            groupMembershipRepository;
    @Mock OrganizationRepository organizationRepository;
    @Mock UserRepository userRepository;
    @Mock ReviewPlanRepository reviewPlanRepository;
    @Mock com.bablsoft.accessflow.core.internal.persistence.repo.CustomJdbcDriverRepository
            customJdbcDriverRepository;
    @Mock CredentialEncryptionService encryptionService;
    @Mock com.bablsoft.accessflow.core.api.SecretResolutionService secretResolutionService;
    @Spy DefaultJdbcCoordinatesFactory coordinatesFactory = new DefaultJdbcCoordinatesFactory();
    @Mock com.bablsoft.accessflow.core.api.DriverCatalogService driverCatalog;
    @Mock com.bablsoft.accessflow.core.api.QueryEngineCatalog engineCatalog;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock org.springframework.context.MessageSource messageSource;
    @Mock com.bablsoft.accessflow.core.api.QuotaService quotaService;
    @InjectMocks DatasourceAdminServiceImpl service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID otherOrgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @Test
    void createEncryptsPasswordAndAppliesDefaults() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Prod"))
                .thenReturn(false);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(encryptionService.encrypt("plaintext-pw")).thenReturn("ENC(plaintext-pw)");
        when(datasourceRepository.save(any(DatasourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var command = new CreateDatasourceCommand(orgId, "Prod", DbType.POSTGRESQL,
                "db.example.com", 5432, "appdb", "svc", "plaintext-pw",
                SslMode.REQUIRE, null, null, null, null, null, false, null, null, null, null, null,
                null, null, null);
        var result = service.create(command);

        assertThat(result.name()).isEqualTo("Prod");
        assertThat(result.dbType()).isEqualTo(DbType.POSTGRESQL);
        assertThat(result.sslMode()).isEqualTo(SslMode.REQUIRE);
        assertThat(result.connectionPoolSize()).isEqualTo(10);
        assertThat(result.maxRowsPerQuery()).isEqualTo(1000);
        assertThat(result.requireReviewWrites()).isTrue();
        assertThat(result.requireReviewReads()).isFalse();
        assertThat(result.aiAnalysisEnabled()).isFalse();
        assertThat(result.active()).isTrue();
        verify(encryptionService).encrypt("plaintext-pw");
    }

    @Test
    void createWithDuplicateNameThrows() {
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Prod"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateDatasourceCommand(orgId, "Prod",
                DbType.POSTGRESQL, "db", 5432, "appdb", "svc", "pw", SslMode.DISABLE,
                null, null, null, null, null, false, null, null, null, null, null, null, null, null)))
                .isInstanceOf(DatasourceNameAlreadyExistsException.class);
        verify(datasourceRepository, never()).save(any());
        verify(driverCatalog, never()).resolve(any());
    }

    @Test
    void createThrowsWhenDatasourceQuotaExceeded() {
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Prod"))
                .thenReturn(false);
        org.mockito.Mockito.doThrow(new com.bablsoft.accessflow.core.api.QuotaExceededException(
                        com.bablsoft.accessflow.core.api.QuotaType.DATASOURCE, orgId, 3, 3))
                .when(quotaService).checkDatasourceQuota(orgId);

        assertThatThrownBy(() -> service.create(new CreateDatasourceCommand(orgId, "Prod",
                DbType.POSTGRESQL, "db", 5432, "appdb", "svc", "pw", SslMode.DISABLE,
                null, null, null, null, null, false, null, null, null, null, null, null, null, null)))
                .isInstanceOf(com.bablsoft.accessflow.core.api.QuotaExceededException.class);
        verify(datasourceRepository, never()).save(any());
    }

    @Test
    void createResolvesDriverBeforePersisting() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Analytics"))
                .thenReturn(false);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(encryptionService.encrypt("pw")).thenReturn("ENC(pw)");
        when(datasourceRepository.save(any(DatasourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var command = new CreateDatasourceCommand(orgId, "Analytics", DbType.MYSQL,
                "db.example.com", 3306, "appdb", "svc", "pw",
                SslMode.REQUIRE, null, null, null, null, null, false, null, null, null, null, null,
                null, null, null);
        service.create(command);

        var inOrder = inOrder(driverCatalog, datasourceRepository, encryptionService);
        inOrder.verify(driverCatalog).resolve(DbType.MYSQL);
        inOrder.verify(encryptionService).encrypt("pw");
        inOrder.verify(datasourceRepository).save(any(DatasourceEntity.class));
    }

    @Test
    void createPropagatesDriverResolutionExceptionAndDoesNotPersist() {
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Analytics"))
                .thenReturn(false);
        when(driverCatalog.resolve(DbType.MYSQL)).thenThrow(new DriverResolutionException(
                DbType.MYSQL, DriverResolutionException.Reason.CHECKSUM_MISMATCH, "boom"));

        var command = new CreateDatasourceCommand(orgId, "Analytics", DbType.MYSQL,
                "db.example.com", 3306, "appdb", "svc", "pw",
                SslMode.REQUIRE, null, null, null, null, null, false, null, null, null, null, null,
                null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(DriverResolutionException.class);
        verify(datasourceRepository, never()).save(any());
        verify(encryptionService, never()).encrypt(any());
        verify(organizationRepository, never()).getReferenceById(any(UUID.class));
    }

    @Test
    void createCassandraWithoutLocalDatacenterThrows() {
        var command = new CreateDatasourceCommand(orgId, "Cass", DbType.CASSANDRA, "node1", 9042,
                "app", "svc", "pw", SslMode.DISABLE, null, null, null, null, null, false, null,
                null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDatasourcePermissionException.class);
        verify(datasourceRepository, never()).save(any());
    }

    @Test
    void createCassandraWithLocalDatacenterSucceedsAndSurfacesIt() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(encryptionService.encrypt("pw")).thenReturn("ENC(pw)");
        when(engineCatalog.isEngineManaged(DbType.CASSANDRA)).thenReturn(true);
        when(datasourceRepository.save(any(DatasourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var command = new CreateDatasourceCommand(orgId, "Cass", DbType.CASSANDRA, "node1", 9042,
                "app", "svc", "pw", SslMode.DISABLE, null, null, null, null, null, false, null,
                null, null, null, null, null, null, null, "dc1");
        var result = service.create(command);

        assertThat(result.localDatacenter()).isEqualTo("dc1");
        verify(engineCatalog).engineFor(DbType.CASSANDRA);
    }

    @Test
    void updateCassandraClearingLocalDatacenterThrows() {
        var entity = buildDatasource(datasourceId, orgId, "Cass");
        entity.setDbType(DbType.CASSANDRA);
        entity.setLocalDatacenter("dc1");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));

        var command = new UpdateDatasourceCommand(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, "  ");
        assertThatThrownBy(() -> service.update(datasourceId, orgId, command))
                .isInstanceOf(IllegalDatasourcePermissionException.class);
    }

    @Test
    void createDynamoDbWithoutRegionThrows() {
        // DynamoDB requires database_name (the AWS region); host/port are unused.
        var command = new CreateDatasourceCommand(orgId, "Dyn", DbType.DYNAMODB, null, null,
                null, "AKIA", "secret", SslMode.DISABLE, null, null, null, null, null, false, null,
                null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDatasourcePermissionException.class);
        verify(datasourceRepository, never()).save(any());
    }

    @Test
    void createDynamoDbWithRegionAndCustomEndpointSucceeds() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(encryptionService.encrypt("secret")).thenReturn("ENC(secret)");
        when(engineCatalog.isEngineManaged(DbType.DYNAMODB)).thenReturn(true);
        when(datasourceRepository.save(any(DatasourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // No host/port; region in database_name and the custom endpoint in jdbc_url_override —
        // which is allowed for DynamoDB even though it is forbidden for every other non-CUSTOM type.
        var command = new CreateDatasourceCommand(orgId, "Dyn", DbType.DYNAMODB, null, null,
                "us-east-1", "AKIA", "secret", SslMode.DISABLE, null, null, null, null, null, false,
                null, null, null, null, "http://localhost:8000", null, null, null, null);
        var result = service.create(command);

        assertThat(result.databaseName()).isEqualTo("us-east-1");
        verify(engineCatalog).engineFor(DbType.DYNAMODB);
    }

    @Test
    void createNeo4jWithoutDatabaseNameThrows() {
        // Neo4j always requires database_name (the Bolt session's target database).
        var command = new CreateDatasourceCommand(orgId, "Graph", DbType.NEO4J, "graph.example.com",
                7687, null, "neo4j", "pw", SslMode.REQUIRE, null, null, null, null, null, false,
                null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDatasourcePermissionException.class);
        verify(datasourceRepository, never()).save(any());
    }

    @Test
    void createNeo4jWithoutHostAndWithoutOverrideThrows() {
        // No host/port and no bolt URI override — nothing to connect to.
        var command = new CreateDatasourceCommand(orgId, "Graph", DbType.NEO4J, null, null,
                "neo4j", "neo4j", "pw", SslMode.REQUIRE, null, null, null, null, null, false,
                null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDatasourcePermissionException.class);
        verify(datasourceRepository, never()).save(any());
    }

    @Test
    void createNeo4jWithHostPortAndDatabaseSucceeds() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(encryptionService.encrypt("pw")).thenReturn("ENC(pw)");
        when(engineCatalog.isEngineManaged(DbType.NEO4J)).thenReturn(true);
        when(datasourceRepository.save(any(DatasourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var command = new CreateDatasourceCommand(orgId, "Graph", DbType.NEO4J, "graph.example.com",
                7687, "neo4j", "neo4j", "pw", SslMode.REQUIRE, null, null, null, null, null, false,
                null, null, null, null, null, null, null, null, null);
        var result = service.create(command);

        assertThat(result.databaseName()).isEqualTo("neo4j");
        verify(engineCatalog).engineFor(DbType.NEO4J);
    }

    @Test
    void createNeo4jWithBoltUriOverrideAndDatabaseSucceeds() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(encryptionService.encrypt("pw")).thenReturn("ENC(pw)");
        when(engineCatalog.isEngineManaged(DbType.NEO4J)).thenReturn(true);
        when(datasourceRepository.save(any(DatasourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // No host/port; a full neo4j+s:// routing URI in jdbc_url_override — allowed for Neo4j
        // (like DynamoDB) even though it is forbidden for every other non-CUSTOM type.
        var command = new CreateDatasourceCommand(orgId, "Aura", DbType.NEO4J, null, null,
                "neo4j", "neo4j", "pw", SslMode.VERIFY_FULL, null, null, null, null, null, false,
                null, null, null, null, "neo4j+s://abc.databases.neo4j.io", null, null, null, null);
        var result = service.create(command);

        assertThat(result.databaseName()).isEqualTo("neo4j");
        verify(engineCatalog).engineFor(DbType.NEO4J);
    }

    @Test
    void createSnowflakeWithoutDatabaseNameThrows() {
        // Snowflake requires the database in database_name; the account host alone is not enough.
        var command = new CreateDatasourceCommand(orgId, "Wh", DbType.SNOWFLAKE,
                "xy1.eu-central-1.snowflakecomputing.com", null, null, "svc", "pw",
                SslMode.REQUIRE, null, null, null, null, null, false, null,
                null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDatasourcePermissionException.class);
        verify(datasourceRepository, never()).save(any());
    }

    @Test
    void createSnowflakeWithoutHostThrows() {
        var command = new CreateDatasourceCommand(orgId, "Wh", DbType.SNOWFLAKE, null, null,
                "ANALYTICS", "svc", "pw", SslMode.REQUIRE, null, null, null, null, null, false,
                null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDatasourcePermissionException.class);
        verify(datasourceRepository, never()).save(any());
    }

    @Test
    void createSnowflakeWithHostDatabaseAndUrlOverrideSucceeds() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(encryptionService.encrypt("pw")).thenReturn("ENC(pw)");
        when(engineCatalog.isEngineManaged(DbType.SNOWFLAKE)).thenReturn(true);
        when(datasourceRepository.save(any(DatasourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Port is unused (always 443); the optional jdbc_url_override carries warehouse/role/schema
        // params — allowed for Snowflake even though it is forbidden for most non-CUSTOM types.
        var command = new CreateDatasourceCommand(orgId, "Wh", DbType.SNOWFLAKE,
                "xy1.eu-central-1.snowflakecomputing.com", null, "ANALYTICS", "svc", "pw",
                SslMode.REQUIRE, null, null, null, null, null, false, null, null, null, null,
                "jdbc:snowflake://xy1.eu-central-1.snowflakecomputing.com/?warehouse=WH&role=GOV",
                null, null, null, null);
        var result = service.create(command);

        assertThat(result.databaseName()).isEqualTo("ANALYTICS");
        verify(engineCatalog).engineFor(DbType.SNOWFLAKE);
    }

    @Test
    void createBigQueryWithInvalidProjectDatasetThrows() {
        // database_name is "project" or "project.dataset" — more than one dot is invalid.
        var command = new CreateDatasourceCommand(orgId, "Bq", DbType.BIGQUERY, null, null,
                "proj.dataset.extra", null, "{\"type\":\"service_account\"}", SslMode.REQUIRE,
                null, null, null, null, null, false, null, null, null, null, null, null, null,
                null, null);
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDatasourcePermissionException.class);
        verify(datasourceRepository, never()).save(any());
    }

    @Test
    void createBigQueryWithProjectDatasetAndEndpointOverrideSucceeds() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(encryptionService.encrypt("{\"type\":\"service_account\"}"))
                .thenReturn("ENC(sa)");
        when(engineCatalog.isEngineManaged(DbType.BIGQUERY)).thenReturn(true);
        when(datasourceRepository.save(any(DatasourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // No host/port/username; project.dataset in database_name and the emulator endpoint in
        // jdbc_url_override — allowed for BigQuery (like DynamoDB's custom endpoint).
        var command = new CreateDatasourceCommand(orgId, "Bq", DbType.BIGQUERY, null, null,
                "my-project.analytics", null, "{\"type\":\"service_account\"}", SslMode.REQUIRE,
                null, null, null, null, null, false, null, null, null, null,
                "http://localhost:9050", null, null, null, null);
        var result = service.create(command);

        assertThat(result.databaseName()).isEqualTo("my-project.analytics");
        verify(engineCatalog).engineFor(DbType.BIGQUERY);
    }

    @Test
    void createBigQueryWithoutCredentialThrows() {
        // BigQuery is password-only: the service-account key JSON is mandatory.
        var command = new CreateDatasourceCommand(orgId, "Bq", DbType.BIGQUERY, null, null,
                "my-project", null, null, SslMode.REQUIRE, null, null, null, null, null, false,
                null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDatasourcePermissionException.class);
        verify(datasourceRepository, never()).save(any());
    }

    @Test
    void createDatabricksWithoutWarehousePathThrows() {
        // Databricks is the one non-CUSTOM dialect where jdbc_url_override is REQUIRED — it
        // carries the SQL warehouse HTTP path the Statement Execution API needs.
        var command = new CreateDatasourceCommand(orgId, "Lake", DbType.DATABRICKS,
                "adb-123.azuredatabricks.net", null, null, null, "dapi-token", SslMode.REQUIRE,
                null, null, null, null, null, false, null, null, null, null, null, null, null,
                null, null);
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDatasourcePermissionException.class);
        verify(datasourceRepository, never()).save(any());
    }

    @Test
    void createDatabricksWithWarehousePathAndCatalogSucceeds() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(encryptionService.encrypt("dapi-token")).thenReturn("ENC(pat)");
        when(engineCatalog.isEngineManaged(DbType.DATABRICKS)).thenReturn(true);
        when(datasourceRepository.save(any(DatasourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var command = new CreateDatasourceCommand(orgId, "Lake", DbType.DATABRICKS,
                "adb-123.azuredatabricks.net", null, "main", null, "dapi-token", SslMode.REQUIRE,
                null, null, null, null, null, false, null, null, null, null,
                "/sql/1.0/warehouses/abc123def456", null, null, null, null);
        var result = service.create(command);

        assertThat(result.databaseName()).isEqualTo("main");
        verify(engineCatalog).engineFor(DbType.DATABRICKS);
    }

    @Test
    void updateAppliesNonNullFieldsAndReencryptsPassword() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        when(encryptionService.encrypt("new-pw")).thenReturn("ENC(new-pw)");

        var command = new UpdateDatasourceCommand(null, "new-host", null, null, null,
                "new-pw", null, 25, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
        var result = service.update(datasourceId, orgId, command);

        assertThat(result.host()).isEqualTo("new-host");
        assertThat(result.connectionPoolSize()).isEqualTo(25);
        assertThat(entity.getPasswordEncrypted()).isEqualTo("ENC(new-pw)");
        verify(encryptionService).encrypt("new-pw");
    }

    @Test
    void createStoresSecretReferenceVerbatimAfterValidation() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Prod"))
                .thenReturn(false);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(secretResolutionService.isReference("vault:secret/prod/db#password")).thenReturn(true);
        var saved = new java.util.concurrent.atomic.AtomicReference<DatasourceEntity>();
        when(datasourceRepository.save(any(DatasourceEntity.class)))
                .thenAnswer(inv -> {
                    saved.set(inv.getArgument(0));
                    return inv.getArgument(0);
                });

        var command = new CreateDatasourceCommand(orgId, "Prod", DbType.POSTGRESQL,
                "db.example.com", 5432, "appdb", "svc", "vault:secret/prod/db#password",
                SslMode.REQUIRE, null, null, null, null, null, false, null, null, null, null, null,
                null, null, null);
        service.create(command);

        assertThat(saved.get().getPasswordEncrypted()).isEqualTo("vault:secret/prod/db#password");
        verify(secretResolutionService).validateReference("vault:secret/prod/db#password");
        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    void createWithDisabledProviderReferenceThrowsAndDoesNotPersist() {
        var organization = new OrganizationEntity();
        organization.setId(orgId);
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Prod"))
                .thenReturn(false);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(organization);
        when(secretResolutionService.isReference("azure:db-password")).thenReturn(true);
        org.mockito.Mockito.doThrow(new com.bablsoft.accessflow.core.api.SecretProviderDisabledException("azure"))
                .when(secretResolutionService).validateReference("azure:db-password");

        var command = new CreateDatasourceCommand(orgId, "Prod", DbType.POSTGRESQL,
                "db.example.com", 5432, "appdb", "svc", "azure:db-password",
                SslMode.REQUIRE, null, null, null, null, null, false, null, null, null, null, null,
                null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(com.bablsoft.accessflow.core.api.SecretProviderDisabledException.class);
        verify(datasourceRepository, never()).save(any());
    }

    @Test
    void updateStoresSecretReferenceVerbatim() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        when(secretResolutionService.isReference("aws:prod/db#password")).thenReturn(true);

        var command = new UpdateDatasourceCommand(null, null, null, null, null,
                "aws:prod/db#password", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        service.update(datasourceId, orgId, command);

        assertThat(entity.getPasswordEncrypted()).isEqualTo("aws:prod/db#password");
        verify(secretResolutionService).validateReference("aws:prod/db#password");
        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    void updateSkipsPasswordReencryptIfNotProvided() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        entity.setPasswordEncrypted("ORIG");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));

        var command = new UpdateDatasourceCommand("Renamed", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCaseAndIdNot(
                eq(orgId), eq("Renamed"), eq(datasourceId))).thenReturn(false);

        service.update(datasourceId, orgId, command);

        assertThat(entity.getPasswordEncrypted()).isEqualTo("ORIG");
        verify(encryptionService, never()).encrypt(any());
    }

    @Test
    void updateRenameToConflictingNameThrows() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCaseAndIdNot(
                eq(orgId), eq("Conflict"), eq(datasourceId))).thenReturn(true);

        assertThatThrownBy(() -> service.update(datasourceId, orgId,
                new UpdateDatasourceCommand("Conflict", null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null)))
                .isInstanceOf(DatasourceNameAlreadyExistsException.class);
    }

    @Test
    void updateOnDifferentOrgThrowsNotFound() {
        var entity = buildDatasource(datasourceId, otherOrgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.update(datasourceId, orgId,
                new UpdateDatasourceCommand(null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null)))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    @Test
    void deactivateSetsActiveFalse() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));

        service.deactivate(datasourceId, orgId);

        assertThat(entity.isActive()).isFalse();
        verify(eventPublisher).publishEvent(new DatasourceDeactivatedEvent(datasourceId));
    }

    @Test
    void deactivateIsIdempotentForAlreadyInactiveDatasource() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        entity.setActive(false);
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));

        service.deactivate(datasourceId, orgId);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void deactivateOnDifferentOrgThrowsNotFound() {
        var entity = buildDatasource(datasourceId, otherOrgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.deactivate(datasourceId, orgId))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    @Test
    void updatePublishesConfigChangedEventWhenPoolFieldsChange() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));

        service.update(datasourceId, orgId, new UpdateDatasourceCommand(
                null, "new-host", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null));

        verify(eventPublisher).publishEvent(new DatasourceConfigChangedEvent(datasourceId));
    }

    @Test
    void updateDoesNotPublishWhenOnlyNonPoolFieldsChange() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));

        service.update(datasourceId, orgId, new UpdateDatasourceCommand(
                null, null, null, null, null, null, null, null, 5000, null, null, null, null,
                null, null, null, null, null, null, null, null));

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void updateAddsReplicaEndpointAndEncryptsPassword() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        when(encryptionService.encrypt("replica-pw")).thenReturn("ENC(replica-pw)");

        service.update(datasourceId, orgId, new UpdateDatasourceCommand(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null,
                "jdbc:postgresql://replica:5432/appdb", "replica-user", "replica-pw", null));

        assertThat(entity.getReadReplicas()).hasSize(1);
        var replica = entity.getReadReplicas().get(0);
        assertThat(replica.getJdbcUrl()).isEqualTo("jdbc:postgresql://replica:5432/appdb");
        assertThat(replica.getUsername()).isEqualTo("replica-user");
        assertThat(replica.getPasswordEncrypted()).isEqualTo("ENC(replica-pw)");
        assertThat(replica.getId()).isNotNull();
        assertThat(replica.getPosition()).isZero();
        verify(eventPublisher).publishEvent(new DatasourceConfigChangedEvent(datasourceId));
    }

    @Test
    void updateWithEmptyReplicaListRemovesAllEndpoints() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        addReplica(entity, "jdbc:postgresql://replica/appdb", "ru", "ENC(rpw)");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));

        service.update(datasourceId, orgId, new UpdateDatasourceCommand(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null,
                "", null, null, null));

        assertThat(entity.getReadReplicas()).isEmpty();
        verify(eventPublisher).publishEvent(new DatasourceConfigChangedEvent(datasourceId));
    }

    @Test
    void updateMergesReplicaListByEndpointId() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        addReplica(entity, "jdbc:postgresql://replica-a/appdb", "ua", "ENC(a)");
        addReplica(entity, "jdbc:postgresql://replica-b/appdb", "ub", "ENC(b)");
        var keptId = entity.getReadReplicas().get(0).getId();
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        when(encryptionService.encrypt("new-pw")).thenReturn("ENC(new-pw)");

        service.update(datasourceId, orgId, new UpdateDatasourceCommand(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null,
                List.of(
                        // Existing endpoint: URL changed, password null → stored secret kept.
                        new ReplicaEndpointInput(keptId,
                                "jdbc:postgresql://replica-a2/appdb", "ua2", null),
                        // New endpoint (no id) with a fresh password.
                        new ReplicaEndpointInput(null,
                                "jdbc:postgresql://replica-c/appdb", "uc", "new-pw")),
                null, null, null, null, null));

        assertThat(entity.getReadReplicas()).hasSize(2);
        var kept = entity.getReadReplicas().get(0);
        assertThat(kept.getId()).isEqualTo(keptId);
        assertThat(kept.getJdbcUrl()).isEqualTo("jdbc:postgresql://replica-a2/appdb");
        assertThat(kept.getUsername()).isEqualTo("ua2");
        assertThat(kept.getPasswordEncrypted()).isEqualTo("ENC(a)");
        assertThat(kept.getPosition()).isZero();
        var added = entity.getReadReplicas().get(1);
        assertThat(added.getJdbcUrl()).isEqualTo("jdbc:postgresql://replica-c/appdb");
        assertThat(added.getPasswordEncrypted()).isEqualTo("ENC(new-pw)");
        assertThat(added.getPosition()).isEqualTo(1);
        verify(eventPublisher).publishEvent(new DatasourceConfigChangedEvent(datasourceId));
    }

    @Test
    void updateReplicaEmptyPasswordClearsStoredSecret() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        addReplica(entity, "jdbc:postgresql://replica/appdb", "ru", "ENC(rpw)");
        var replicaId = entity.getReadReplicas().get(0).getId();
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));

        service.update(datasourceId, orgId, new UpdateDatasourceCommand(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null,
                List.of(new ReplicaEndpointInput(replicaId,
                        "jdbc:postgresql://replica/appdb", "ru", "")),
                null, null, null, null, null));

        assertThat(entity.getReadReplicas().get(0).getPasswordEncrypted()).isNull();
    }

    @Test
    void updateCacheSettingsPublishesCacheConfigChangedWithoutPoolEviction() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));

        service.update(datasourceId, orgId, new UpdateDatasourceCommand(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, true, 120));

        assertThat(entity.isResultCacheEnabled()).isTrue();
        assertThat(entity.getResultCacheTtlSeconds()).isEqualTo(120);
        verify(eventPublisher).publishEvent(new DatasourceCacheConfigChangedEvent(datasourceId));
        verify(eventPublisher, never()).publishEvent(any(DatasourceConfigChangedEvent.class));
    }

    @Test
    void testReplicaWithBlankUrlThrowsDatasourceConnectionTestException() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        when(messageSource.getMessage(eq("error.replica_not_configured"), any(), any()))
                .thenReturn("not configured");

        assertThatThrownBy(() -> service.testReplica(datasourceId, orgId,
                new com.bablsoft.accessflow.core.api.TestReplicaCommand("", "u", "pw")))
                .isInstanceOf(com.bablsoft.accessflow.core.api.DatasourceConnectionTestException.class);
    }

    @Test
    void testReplicaWithoutPasswordAndNoPersistedThrowsDatasourceConnectionTestException() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        // No persisted replica password.
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        when(messageSource.getMessage(eq("error.replica_not_configured"), any(), any()))
                .thenReturn("not configured");

        assertThatThrownBy(() -> service.testReplica(datasourceId, orgId,
                new com.bablsoft.accessflow.core.api.TestReplicaCommand(
                        "jdbc:postgresql://r/db", "u", null)))
                .isInstanceOf(com.bablsoft.accessflow.core.api.DatasourceConnectionTestException.class);
    }

    @Test
    void testReplicaWithReplicaIdButNoStoredSecretThrowsDatasourceConnectionTestException() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        addReplica(entity, "jdbc:postgresql://replica/appdb", "ru", null);
        var replicaId = entity.getReadReplicas().get(0).getId();
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        when(messageSource.getMessage(eq("error.replica_not_configured"), any(), any()))
                .thenReturn("not configured");

        assertThatThrownBy(() -> service.testReplica(datasourceId, orgId,
                new com.bablsoft.accessflow.core.api.TestReplicaCommand(
                        "jdbc:postgresql://replica/appdb", "ru", null, replicaId)))
                .isInstanceOf(com.bablsoft.accessflow.core.api.DatasourceConnectionTestException.class);
    }

    @Test
    void updatePublishesDeactivatedWhenActiveFlipsToFalse() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));

        service.update(datasourceId, orgId, new UpdateDatasourceCommand(
                null, "new-host", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, false));

        verify(eventPublisher).publishEvent(new DatasourceDeactivatedEvent(datasourceId));
        verify(eventPublisher, never()).publishEvent(any(DatasourceConfigChangedEvent.class));
    }

    @Test
    void getForUserWithoutPermissionThrowsNotFound() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        when(datasourceRepository.existsVisibleToUser(eq(datasourceId), eq(userId), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.getForUser(datasourceId, orgId, userId))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    @Test
    void getForUserWithPermissionReturnsView() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        when(datasourceRepository.existsVisibleToUser(eq(datasourceId), eq(userId), any()))
                .thenReturn(true);

        var view = service.getForUser(datasourceId, orgId, userId);

        assertThat(view.id()).isEqualTo(datasourceId);
    }

    @Test
    void grantPermissionRejectsUserFromDifferentOrg() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        var otherOrg = new OrganizationEntity();
        otherOrg.setId(otherOrgId);
        var user = new UserEntity();
        user.setId(userId);
        user.setOrganization(otherOrg);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var command = new CreatePermissionCommand(userId, true, false, false, false, null, null,
                null, null, null);
        assertThatThrownBy(() -> service.grantPermission(datasourceId, orgId, adminId, command))
                .isInstanceOf(IllegalDatasourcePermissionException.class);
    }

    @Test
    void grantPermissionRejectsUnknownUser() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        var command = new CreatePermissionCommand(userId, true, false, false, false, null, null,
                null, null, null);
        assertThatThrownBy(() -> service.grantPermission(datasourceId, orgId, adminId, command))
                .isInstanceOf(IllegalDatasourcePermissionException.class);
    }

    @Test
    void grantPermissionRejectsDuplicate() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        var org = new OrganizationEntity();
        org.setId(orgId);
        var user = new UserEntity();
        user.setId(userId);
        user.setOrganization(org);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(permissionRepository.existsByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(true);

        var command = new CreatePermissionCommand(userId, true, false, false, false, null, null,
                null, null, null);
        assertThatThrownBy(() -> service.grantPermission(datasourceId, orgId, adminId, command))
                .isInstanceOf(DatasourcePermissionAlreadyExistsException.class);
    }

    @Test
    void grantPermissionPersistsAndReturnsView() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        var org = new OrganizationEntity();
        org.setId(orgId);
        var user = new UserEntity();
        user.setId(userId);
        user.setEmail("alice@example.com");
        user.setDisplayName("Alice");
        user.setOrganization(org);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(permissionRepository.existsByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(false);
        var grantedBy = new UserEntity();
        grantedBy.setId(adminId);
        when(userRepository.getReferenceById(adminId)).thenReturn(grantedBy);
        when(permissionRepository.save(any(DatasourceUserPermissionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var command = new CreatePermissionCommand(userId, true, true, false, true, 500,
                List.of("public"), List.of("orders"), List.of("public.orders.ssn"), null);
        var view = service.grantPermission(datasourceId, orgId, adminId, command);

        assertThat(view.userId()).isEqualTo(userId);
        assertThat(view.userEmail()).isEqualTo("alice@example.com");
        assertThat(view.canRead()).isTrue();
        assertThat(view.canWrite()).isTrue();
        assertThat(view.canDdl()).isFalse();
        assertThat(view.canBreakGlass()).isTrue();
        assertThat(view.allowedSchemas()).containsExactly("public");
        assertThat(view.allowedTables()).containsExactly("orders");
        assertThat(view.restrictedColumns()).containsExactly("public.orders.ssn");
        assertThat(view.rowLimitOverride()).isEqualTo(500);
        assertThat(view.createdBy()).isEqualTo(adminId);
    }

    @Test
    void revokePermissionDeletesWhenFound() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        var permId = UUID.randomUUID();
        var permission = new DatasourceUserPermissionEntity();
        permission.setId(permId);
        permission.setDatasource(entity);
        when(permissionRepository.findById(permId)).thenReturn(Optional.of(permission));

        service.revokePermission(datasourceId, orgId, permId);

        verify(permissionRepository).delete(permission);
    }

    @Test
    void revokePermissionThrowsWhenMissing() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        var permId = UUID.randomUUID();
        when(permissionRepository.findById(permId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revokePermission(datasourceId, orgId, permId))
                .isInstanceOf(DatasourcePermissionNotFoundException.class);
    }

    @Test
    void revokePermissionThrowsWhenPermissionBelongsToOtherDatasource() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));

        var otherDatasource = buildDatasource(UUID.randomUUID(), orgId, "Other");
        var permission = new DatasourceUserPermissionEntity();
        var permId = UUID.randomUUID();
        permission.setId(permId);
        permission.setDatasource(otherDatasource);
        when(permissionRepository.findById(permId)).thenReturn(Optional.of(permission));

        assertThatThrownBy(() -> service.revokePermission(datasourceId, orgId, permId))
                .isInstanceOf(DatasourcePermissionNotFoundException.class);
    }

    @Test
    void grantGroupPermissionPersistsAndReturnsView() {
        var groupId = UUID.randomUUID();
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        when(userGroupService.getGroup(groupId, orgId)).thenReturn(new com.bablsoft.accessflow.core.api.UserGroupView(
                groupId, orgId, "Analysts", null, 4, java.time.Instant.now(), java.time.Instant.now()));
        when(groupPermissionRepository.existsByGroup_IdAndDatasource_Id(groupId, datasourceId))
                .thenReturn(false);
        var group = new com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity();
        group.setId(groupId);
        group.setName("Analysts");
        when(userGroupRepository.getReferenceById(groupId)).thenReturn(group);
        var grantedBy = new UserEntity();
        grantedBy.setId(adminId);
        when(userRepository.getReferenceById(adminId)).thenReturn(grantedBy);
        when(groupMembershipRepository.countByGroup_Id(groupId)).thenReturn(4L);
        when(groupPermissionRepository.save(any(
                com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceGroupPermissionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var command = new com.bablsoft.accessflow.core.api.CreateDatasourceGroupPermissionCommand(
                groupId, true, true, false, false, null, List.of("public"), null, null, null);
        var view = service.grantGroupPermission(datasourceId, orgId, adminId, command);

        assertThat(view.groupId()).isEqualTo(groupId);
        assertThat(view.groupName()).isEqualTo("Analysts");
        assertThat(view.memberCount()).isEqualTo(4);
        assertThat(view.canRead()).isTrue();
        assertThat(view.canWrite()).isTrue();
        assertThat(view.allowedSchemas()).containsExactly("public");
        assertThat(view.createdBy()).isEqualTo(adminId);
    }

    @Test
    void grantGroupPermissionRejectsDuplicate() {
        var groupId = UUID.randomUUID();
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        when(userGroupService.getGroup(groupId, orgId)).thenReturn(new com.bablsoft.accessflow.core.api.UserGroupView(
                groupId, orgId, "Analysts", null, 4, java.time.Instant.now(), java.time.Instant.now()));
        when(groupPermissionRepository.existsByGroup_IdAndDatasource_Id(groupId, datasourceId))
                .thenReturn(true);

        var command = new com.bablsoft.accessflow.core.api.CreateDatasourceGroupPermissionCommand(
                groupId, true, false, false, false, null, null, null, null, null);
        assertThatThrownBy(() -> service.grantGroupPermission(datasourceId, orgId, adminId, command))
                .isInstanceOf(com.bablsoft.accessflow.core.api.DatasourceGroupPermissionAlreadyExistsException.class);
    }

    @Test
    void grantGroupPermissionRejectsUnknownGroup() {
        var groupId = UUID.randomUUID();
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        when(userGroupService.getGroup(groupId, orgId))
                .thenThrow(new com.bablsoft.accessflow.core.api.UserGroupNotFoundException(groupId));

        var command = new com.bablsoft.accessflow.core.api.CreateDatasourceGroupPermissionCommand(
                groupId, true, false, false, false, null, null, null, null, null);
        assertThatThrownBy(() -> service.grantGroupPermission(datasourceId, orgId, adminId, command))
                .isInstanceOf(com.bablsoft.accessflow.core.api.UserGroupNotFoundException.class);
    }

    @Test
    void listGroupPermissionsReturnsViews() {
        var groupId = UUID.randomUUID();
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        var group = new com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity();
        group.setId(groupId);
        group.setName("Analysts");
        var perm = new com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceGroupPermissionEntity();
        perm.setId(UUID.randomUUID());
        perm.setDatasource(entity);
        perm.setGroup(group);
        perm.setCanRead(true);
        when(groupPermissionRepository.findAllByDatasource_Id(datasourceId)).thenReturn(List.of(perm));
        when(groupMembershipRepository.countByGroup_Id(groupId)).thenReturn(2L);

        var views = service.listGroupPermissions(datasourceId, orgId);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).groupName()).isEqualTo("Analysts");
        assertThat(views.get(0).memberCount()).isEqualTo(2);
    }

    @Test
    void revokeGroupPermissionDeletesWhenFound() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        var permId = UUID.randomUUID();
        var perm = new com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceGroupPermissionEntity();
        perm.setId(permId);
        perm.setDatasource(entity);
        when(groupPermissionRepository.findById(permId)).thenReturn(Optional.of(perm));

        service.revokeGroupPermission(datasourceId, orgId, permId);

        verify(groupPermissionRepository).delete(perm);
    }

    @Test
    void revokeGroupPermissionThrowsWhenMissing() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        var permId = UUID.randomUUID();
        when(groupPermissionRepository.findById(permId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revokeGroupPermission(datasourceId, orgId, permId))
                .isInstanceOf(DatasourcePermissionNotFoundException.class);
    }

    @Test
    void getForAdminReturnsViewWhenInOrg() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));

        var view = service.getForAdmin(datasourceId, orgId);

        assertThat(view.id()).isEqualTo(datasourceId);
        assertThat(view.organizationId()).isEqualTo(orgId);
    }

    @Test
    void getForAdminThrowsWhenInOtherOrg() {
        var entity = buildDatasource(datasourceId, otherOrgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.getForAdmin(datasourceId, orgId))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    @Test
    void getForAdminThrowsWhenNotFound() {
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getForAdmin(datasourceId, orgId))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    @Test
    void createMongoDatasourceResolvesEnginePluginBeforePersisting() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Docs"))
                .thenReturn(false);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(encryptionService.encrypt("pw")).thenReturn("ENC(pw)");
        when(datasourceRepository.save(any(DatasourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(engineCatalog.isEngineManaged(DbType.MONGODB)).thenReturn(true);

        var command = new CreateDatasourceCommand(orgId, "Docs", DbType.MONGODB,
                "mongo.example.com", 27017, "appdb", "svc", "pw",
                SslMode.REQUIRE, null, null, null, null, null, false, null, null, null, null, null,
                null, null, null);
        service.create(command);

        var inOrder = inOrder(engineCatalog, datasourceRepository);
        inOrder.verify(engineCatalog).engineFor(DbType.MONGODB);
        inOrder.verify(datasourceRepository).save(any(DatasourceEntity.class));
        verify(driverCatalog, never()).resolve(any());
    }

    @Test
    void createMongoDatasourcePropagatesEngineResolutionFailureAndDoesNotPersist() {
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Docs"))
                .thenReturn(false);
        when(engineCatalog.isEngineManaged(DbType.MONGODB)).thenReturn(true);
        when(engineCatalog.engineFor(DbType.MONGODB)).thenThrow(new DriverResolutionException(
                DbType.MONGODB, DriverResolutionException.Reason.OFFLINE_CACHE_MISS, "offline"));

        var command = new CreateDatasourceCommand(orgId, "Docs", DbType.MONGODB,
                "mongo.example.com", 27017, "appdb", "svc", "pw",
                SslMode.REQUIRE, null, null, null, null, null, false, null, null, null, null, null,
                null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(DriverResolutionException.class);
        verify(datasourceRepository, never()).save(any());
    }

    @Test
    void testMongoDatasourceDelegatesToEngineFromCatalog() {
        var entity = buildDatasource(datasourceId, orgId, "Docs");
        entity.setDbType(DbType.MONGODB);
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        var engine = org.mockito.Mockito.mock(com.bablsoft.accessflow.core.api.QueryEngine.class);
        when(engineCatalog.isEngineManaged(DbType.MONGODB)).thenReturn(true);
        when(engineCatalog.engineFor(DbType.MONGODB)).thenReturn(engine);
        var expected = new com.bablsoft.accessflow.core.api.ConnectionTestResult(true, 5, "ok");
        when(engine.testConnection(any())).thenReturn(expected);

        var result = service.test(datasourceId, orgId);

        assertThat(result).isSameAs(expected);
        verify(engine).testConnection(any());
        verify(driverCatalog, never()).resolve(any());
    }

    @Test
    void introspectMongoDatasourceDelegatesToEngineFromCatalog() {
        var entity = buildDatasource(datasourceId, orgId, "Docs");
        entity.setDbType(DbType.MONGODB);
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        var engine = org.mockito.Mockito.mock(com.bablsoft.accessflow.core.api.QueryEngine.class);
        when(engineCatalog.isEngineManaged(DbType.MONGODB)).thenReturn(true);
        when(engineCatalog.engineFor(DbType.MONGODB)).thenReturn(engine);
        var schema = new com.bablsoft.accessflow.core.api.DatabaseSchemaView(java.util.List.of());
        when(engine.introspectSchema(any())).thenReturn(schema);

        var result = service.introspectSchema(datasourceId, orgId, UUID.randomUUID(), true);

        assertThat(result).isSameAs(schema);
        verify(driverCatalog, never()).resolve(any());
    }

    private DatasourceEntity buildDatasource(UUID id, UUID organizationId, String name) {
        var org = new OrganizationEntity();
        org.setId(organizationId);
        var entity = new DatasourceEntity();
        entity.setId(id);
        entity.setOrganization(org);
        entity.setName(name);
        entity.setDbType(DbType.POSTGRESQL);
        entity.setHost("db");
        entity.setPort(5432);
        entity.setDatabaseName("appdb");
        entity.setUsername("svc");
        entity.setPasswordEncrypted("ENC(secret)");
        entity.setSslMode(SslMode.DISABLE);
        entity.setConnectionPoolSize(10);
        entity.setMaxRowsPerQuery(1000);
        entity.setRequireReviewReads(false);
        entity.setRequireReviewWrites(true);
        entity.setAiAnalysisEnabled(false);
        entity.setActive(true);
        return entity;
    }

    private static void addReplica(DatasourceEntity entity, String jdbcUrl, String username,
                                   String passwordEncrypted) {
        var replica = new DatasourceReadReplicaEntity();
        replica.setId(UUID.randomUUID());
        replica.setDatasource(entity);
        replica.setJdbcUrl(jdbcUrl);
        replica.setUsername(username);
        replica.setPasswordEncrypted(passwordEncrypted);
        replica.setPosition(entity.getReadReplicas().size());
        entity.getReadReplicas().add(replica);
    }

    @Test
    void createWithAiEnabledButNoConfigThrows() {
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Prod"))
                .thenReturn(false);
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(encryptionService.encrypt(any())).thenReturn("ENC");

        var command = new CreateDatasourceCommand(orgId, "Prod", DbType.POSTGRESQL,
                "db", 5432, "appdb", "svc", "pw", SslMode.DISABLE,
                null, null, null, null, null, true, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(MissingAiConfigForDatasourceException.class);
        verify(datasourceRepository, never()).save(any());
    }

    @Test
    void createWithValidAiConfigPersistsBinding() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Prod"))
                .thenReturn(false);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(encryptionService.encrypt("pw")).thenReturn("ENC");
        when(datasourceRepository.save(any(DatasourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        var aiConfigId = UUID.randomUUID();

        var command = new CreateDatasourceCommand(orgId, "Prod", DbType.POSTGRESQL,
                "db", 5432, "appdb", "svc", "pw", SslMode.DISABLE,
                null, null, null, null, null, true, aiConfigId, null, null, null, null, null, null, null);
        var view = service.create(command);

        assertThat(view.aiConfigId()).isEqualTo(aiConfigId);
        assertThat(view.aiAnalysisEnabled()).isTrue();
    }

    @Test
    void updateClearAiConfigUnbindsAndRequiresAiDisabled() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        entity.setAiAnalysisEnabled(true);
        entity.setAiConfigId(UUID.randomUUID());
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.update(datasourceId, orgId,
                new UpdateDatasourceCommand(null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, true, null,
                        null, null, null, null)))
                .isInstanceOf(MissingAiConfigForDatasourceException.class);
    }

    @Test
    void createWithTextToSqlEnabledButNoConfigThrows() {
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Prod"))
                .thenReturn(false);
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(encryptionService.encrypt(any())).thenReturn("ENC");

        // ai_analysis disabled, but text-to-SQL enabled with no bound config -> rejected.
        var command = new CreateDatasourceCommand(orgId, "Prod", DbType.POSTGRESQL,
                "db", 5432, "appdb", "svc", "pw", SslMode.DISABLE,
                null, null, null, null, null, false, null, true, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(MissingAiConfigForDatasourceException.class);
        verify(datasourceRepository, never()).save(any());
    }

    @Test
    void createWithTextToSqlEnabledAndConfigPersistsFlag() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Prod"))
                .thenReturn(false);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(encryptionService.encrypt("pw")).thenReturn("ENC");
        when(datasourceRepository.save(any(DatasourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        var aiConfigId = UUID.randomUUID();

        var command = new CreateDatasourceCommand(orgId, "Prod", DbType.POSTGRESQL,
                "db", 5432, "appdb", "svc", "pw", SslMode.DISABLE,
                null, null, null, null, null, false, aiConfigId, true, null, null, null, null, null, null);
        var view = service.create(command);

        assertThat(view.textToSqlEnabled()).isTrue();
        assertThat(view.aiAnalysisEnabled()).isFalse();
        assertThat(view.aiConfigId()).isEqualTo(aiConfigId);
    }

    @Test
    void updateEnablingTextToSqlWithoutConfigThrows() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        entity.setAiAnalysisEnabled(false);
        entity.setAiConfigId(null);
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.update(datasourceId, orgId,
                new UpdateDatasourceCommand(null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, true, null, null,
                        null, null, null, null)))
                .isInstanceOf(MissingAiConfigForDatasourceException.class);
    }


    @Test
    void probeSqlUsesFromDualForOracle() {
        assertThat(DatasourceAdminServiceImpl.probeSql(DbType.ORACLE)).isEqualTo("SELECT 1 FROM DUAL");
    }

    @Test
    void probeSqlUsesPlainSelectOneForOtherDbTypes() {
        assertThat(DatasourceAdminServiceImpl.probeSql(DbType.POSTGRESQL)).isEqualTo("SELECT 1");
        assertThat(DatasourceAdminServiceImpl.probeSql(DbType.MYSQL)).isEqualTo("SELECT 1");
        assertThat(DatasourceAdminServiceImpl.probeSql(DbType.MARIADB)).isEqualTo("SELECT 1");
        assertThat(DatasourceAdminServiceImpl.probeSql(DbType.MSSQL)).isEqualTo("SELECT 1");
    }

    @Test
    void createCustomDatasourcePersistsCustomDriverAndJdbcUrlOverride() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        var customDriverId = UUID.randomUUID();
        var customDriverEntity = sampleCustomDriverEntity(customDriverId, org, DbType.CUSTOM);
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Snowflake"))
                .thenReturn(false);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(encryptionService.encrypt("pw")).thenReturn("ENC(pw)");
        when(customJdbcDriverRepository.findByIdAndOrganization_Id(customDriverId, orgId))
                .thenReturn(Optional.of(customDriverEntity));
        when(datasourceRepository.save(any(DatasourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var command = new CreateDatasourceCommand(orgId, "Snowflake", DbType.CUSTOM,
                null, null, null, "svc", "pw", SslMode.DISABLE,
                null, null, null, null, null, false, null, null, customDriverId, null,
                "jdbc:snowflake://acme.snowflakecomputing.com/?db=PROD",
                null, null, null);

        var view = service.create(command);

        assertThat(view.dbType()).isEqualTo(DbType.CUSTOM);
        assertThat(view.customDriverId()).isEqualTo(customDriverId);
        assertThat(view.jdbcUrlOverride()).startsWith("jdbc:snowflake://");
        assertThat(view.host()).isNull();
        // The bundled driver catalog must not be queried for a custom datasource.
        verify(driverCatalog, never()).resolve(any());
    }

    @Test
    void createCustomDatasourceRequiresCustomDriverId() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Snowflake"))
                .thenReturn(false);

        var command = new CreateDatasourceCommand(orgId, "Snowflake", DbType.CUSTOM,
                null, null, null, "svc", "pw", SslMode.DISABLE,
                null, null, null, null, null, false, null, null, null, null,
                "jdbc:snowflake://acme.snowflakecomputing.com/", null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDatasourcePermissionException.class)
                .hasMessageContaining("custom_driver_id");
    }

    @Test
    void createCustomDatasourceRequiresJdbcUrlOverride() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        var customDriverId = UUID.randomUUID();
        var customDriverEntity = sampleCustomDriverEntity(customDriverId, org, DbType.CUSTOM);
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Snowflake"))
                .thenReturn(false);
        when(customJdbcDriverRepository.findByIdAndOrganization_Id(customDriverId, orgId))
                .thenReturn(Optional.of(customDriverEntity));

        var command = new CreateDatasourceCommand(orgId, "Snowflake", DbType.CUSTOM,
                null, null, null, "svc", "pw", SslMode.DISABLE,
                null, null, null, null, null, false, null, null, customDriverId, null, null,
                null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDatasourcePermissionException.class)
                .hasMessageContaining("jdbc_url_override");
    }

    @Test
    void createCustomDatasourceRejectsHostPortDatabase() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        var customDriverId = UUID.randomUUID();
        var customDriverEntity = sampleCustomDriverEntity(customDriverId, org, DbType.CUSTOM);
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Snowflake"))
                .thenReturn(false);
        when(customJdbcDriverRepository.findByIdAndOrganization_Id(customDriverId, orgId))
                .thenReturn(Optional.of(customDriverEntity));

        var command = new CreateDatasourceCommand(orgId, "Snowflake", DbType.CUSTOM,
                "host.example.com", 1234, null, "svc", "pw", SslMode.DISABLE,
                null, null, null, null, null, false, null, null, customDriverId, null,
                "jdbc:snowflake://x/", null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDatasourcePermissionException.class)
                .hasMessageContaining("must not set host/port");
    }

    @Test
    void createBundledDatasourceWithJdbcUrlOverrideIsRejected() {
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Bundled"))
                .thenReturn(false);

        var command = new CreateDatasourceCommand(orgId, "Bundled", DbType.POSTGRESQL,
                "h", 5432, "db", "svc", "pw", SslMode.DISABLE,
                null, null, null, null, null, false, null, null, null, null,
                "jdbc:postgresql://h:5432/db", null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDatasourcePermissionException.class)
                .hasMessageContaining("jdbc_url_override is only allowed");
    }

    @Test
    void createBundledDatasourceWithMismatchedCustomDriverIsRejected() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        var customDriverId = UUID.randomUUID();
        var oracleDriver = sampleCustomDriverEntity(customDriverId, org, DbType.ORACLE);
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Prod"))
                .thenReturn(false);
        when(customJdbcDriverRepository.findByIdAndOrganization_Id(customDriverId, orgId))
                .thenReturn(Optional.of(oracleDriver));

        // PostgreSQL datasource binding to an Oracle-target uploaded driver — refused.
        var command = new CreateDatasourceCommand(orgId, "Prod", DbType.POSTGRESQL,
                "h", 5432, "db", "svc", "pw", SslMode.DISABLE,
                null, null, null, null, null, false, null, null, customDriverId, null, null,
                null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDatasourcePermissionException.class)
                .hasMessageContaining("target_db_type");
    }

    @Test
    void createWithUnknownCustomDriverIdThrowsCustomDriverNotFound() {
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Prod"))
                .thenReturn(false);
        var customDriverId = UUID.randomUUID();
        when(customJdbcDriverRepository.findByIdAndOrganization_Id(customDriverId, orgId))
                .thenReturn(Optional.empty());

        var command = new CreateDatasourceCommand(orgId, "Prod", DbType.POSTGRESQL,
                "h", 5432, "db", "svc", "pw", SslMode.DISABLE,
                null, null, null, null, null, false, null, null, customDriverId, null, null,
                null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(com.bablsoft.accessflow.core.api.CustomDriverNotFoundException.class);
    }

    @Test
    void readForeignKeysParsesImportedKeysIntoForeignKeyRecords() throws Exception {
        var md = org.mockito.Mockito.mock(java.sql.DatabaseMetaData.class);
        var rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        when(md.getImportedKeys(null, "public", "child")).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("PKTABLE_SCHEM")).thenReturn("public", "public");
        when(rs.getString("FKCOLUMN_NAME")).thenReturn("parent_id", "owner_id");
        when(rs.getString("PKTABLE_NAME")).thenReturn("parent", "users");
        when(rs.getString("PKCOLUMN_NAME")).thenReturn("id", "id");

        var result = invokeReadForeignKeys(md, "public", "child",
                java.util.Set.of("pg_catalog", "information_schema"));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).fromColumn()).isEqualTo("parent_id");
        assertThat(result.get(0).toTable()).isEqualTo("parent");
        assertThat(result.get(0).toColumn()).isEqualTo("id");
        assertThat(result.get(1).fromColumn()).isEqualTo("owner_id");
        assertThat(result.get(1).toTable()).isEqualTo("users");
    }

    @Test
    void readForeignKeysSkipsRowsReferencingSystemSchemas() throws Exception {
        var md = org.mockito.Mockito.mock(java.sql.DatabaseMetaData.class);
        var rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        when(md.getImportedKeys(null, "public", "child")).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("PKTABLE_SCHEM")).thenReturn("pg_catalog", "public");
        when(rs.getString("FKCOLUMN_NAME")).thenReturn("parent_id");
        when(rs.getString("PKTABLE_NAME")).thenReturn("parent");
        when(rs.getString("PKCOLUMN_NAME")).thenReturn("id");

        var result = invokeReadForeignKeys(md, "public", "child",
                java.util.Set.of("pg_catalog", "information_schema"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).fromColumn()).isEqualTo("parent_id");
    }

    @Test
    void readForeignKeysReturnsEmptyWhenDriverThrows() throws Exception {
        var md = org.mockito.Mockito.mock(java.sql.DatabaseMetaData.class);
        when(md.getImportedKeys(null, "public", "child"))
                .thenThrow(new java.sql.SQLFeatureNotSupportedException("not supported"));

        var result = invokeReadForeignKeys(md, "public", "child", java.util.Set.of());

        assertThat(result).isEmpty();
    }

    @Test
    void readForeignKeysSkipsRowsWithMissingColumnInformation() throws Exception {
        var md = org.mockito.Mockito.mock(java.sql.DatabaseMetaData.class);
        var rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        when(md.getImportedKeys(null, "public", "child")).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("PKTABLE_SCHEM")).thenReturn("public", "public");
        when(rs.getString("FKCOLUMN_NAME")).thenReturn(null, "parent_id");
        when(rs.getString("PKTABLE_NAME")).thenReturn("parent", "parent");
        when(rs.getString("PKCOLUMN_NAME")).thenReturn("id", "id");

        var result = invokeReadForeignKeys(md, "public", "child", java.util.Set.of());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).fromColumn()).isEqualTo("parent_id");
    }

    @SuppressWarnings("unchecked")
    private java.util.List<com.bablsoft.accessflow.core.api.DatabaseSchemaView.ForeignKey>
            invokeReadForeignKeys(java.sql.DatabaseMetaData md, String schema, String table,
                                  java.util.Set<String> systemSchemas) throws Exception {
        var method = DatasourceAdminServiceImpl.class.getDeclaredMethod(
                "readForeignKeys", java.sql.DatabaseMetaData.class, String.class, String.class,
                String.class, java.util.Set.class);
        method.setAccessible(true);
        return (java.util.List<com.bablsoft.accessflow.core.api.DatabaseSchemaView.ForeignKey>)
                method.invoke(service, md, null, schema, table, systemSchemas);
    }

    private com.bablsoft.accessflow.core.internal.persistence.entity.CustomJdbcDriverEntity
            sampleCustomDriverEntity(UUID id, OrganizationEntity org, DbType targetDbType) {
        var entity = new com.bablsoft.accessflow.core.internal.persistence.entity
                .CustomJdbcDriverEntity();
        entity.setId(id);
        entity.setOrganization(org);
        entity.setTargetDbType(targetDbType);
        entity.setVendorName("Acme");
        entity.setDriverClass("com.acme.JdbcDriver");
        entity.setJarFilename("acme.jar");
        entity.setJarSha256("a".repeat(64));
        entity.setJarSizeBytes(1024);
        entity.setStoragePath("custom/x.jar");
        return entity;
    }

    @Test
    void createConnectorDatasourceResolvesConnectorAndPersists() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Analytics"))
                .thenReturn(false);
        when(organizationRepository.getReferenceById(orgId)).thenReturn(org);
        when(encryptionService.encrypt("pw")).thenReturn("ENC(pw)");
        when(datasourceRepository.save(any(DatasourceEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var command = new CreateDatasourceCommand(orgId, "Analytics", DbType.CUSTOM,
                "ch.example.com", 8123, "analytics", "svc", "pw", SslMode.DISABLE,
                null, null, null, null, null, false, null, null, null, "clickhouse", null,
                null, null, null);

        var view = service.create(command);

        assertThat(view.dbType()).isEqualTo(DbType.CUSTOM);
        assertThat(view.connectorId()).isEqualTo("clickhouse");
        assertThat(view.host()).isEqualTo("ch.example.com");
        verify(driverCatalog).resolveConnector("clickhouse");
        verify(driverCatalog, never()).resolve(any());
    }

    @Test
    void createConnectorDatasourceRejectsBothConnectorAndCustomDriver() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        var customDriverId = UUID.randomUUID();
        var customDriverEntity = sampleCustomDriverEntity(customDriverId, org, DbType.CUSTOM);
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Analytics"))
                .thenReturn(false);
        when(customJdbcDriverRepository.findByIdAndOrganization_Id(customDriverId, orgId))
                .thenReturn(Optional.of(customDriverEntity));

        var command = new CreateDatasourceCommand(orgId, "Analytics", DbType.CUSTOM,
                "ch.example.com", 8123, "analytics", "svc", "pw", SslMode.DISABLE,
                null, null, null, null, null, false, null, null, customDriverId, "clickhouse",
                null, null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDatasourcePermissionException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void createConnectorDatasourceRequiresHostPortDatabase() {
        var org = new OrganizationEntity();
        org.setId(orgId);
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Analytics"))
                .thenReturn(false);

        var command = new CreateDatasourceCommand(orgId, "Analytics", DbType.CUSTOM,
                null, 8123, "analytics", "svc", "pw", SslMode.DISABLE,
                null, null, null, null, null, false, null, null, null, "clickhouse", null,
                null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDatasourcePermissionException.class)
                .hasMessageContaining("host is required");
    }

    @Test
    void createBundledDatasourceWithConnectorIdIsRejected() {
        when(datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(orgId, "Prod"))
                .thenReturn(false);

        var command = new CreateDatasourceCommand(orgId, "Prod", DbType.POSTGRESQL,
                "h", 5432, "db", "svc", "pw", SslMode.DISABLE,
                null, null, null, null, null, false, null, null, null, "clickhouse", null,
                null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDatasourcePermissionException.class)
                .hasMessageContaining("connector_id is only allowed");
    }
}
