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
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UpdateDatasourceCommand;
import com.bablsoft.accessflow.core.events.DatasourceConfigChangedEvent;
import com.bablsoft.accessflow.core.events.DatasourceDeactivatedEvent;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
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
    @Mock OrganizationRepository organizationRepository;
    @Mock UserRepository userRepository;
    @Mock ReviewPlanRepository reviewPlanRepository;
    @Mock com.bablsoft.accessflow.core.internal.persistence.repo.CustomJdbcDriverRepository
            customJdbcDriverRepository;
    @Mock CredentialEncryptionService encryptionService;
    @Spy DefaultJdbcCoordinatesFactory coordinatesFactory = new DefaultJdbcCoordinatesFactory();
    @Mock com.bablsoft.accessflow.core.api.DriverCatalogService driverCatalog;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock org.springframework.context.MessageSource messageSource;
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
                SslMode.REQUIRE, null, null, null, null, null, false, null, null, null, null,
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
                null, null, null, null, null, false, null, null, null, null, null, null, null)))
                .isInstanceOf(DatasourceNameAlreadyExistsException.class);
        verify(datasourceRepository, never()).save(any());
        verify(driverCatalog, never()).resolve(any());
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
                SslMode.REQUIRE, null, null, null, null, null, false, null, null, null, null,
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
                SslMode.REQUIRE, null, null, null, null, null, false, null, null, null, null,
                null, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(DriverResolutionException.class);
        verify(datasourceRepository, never()).save(any());
        verify(encryptionService, never()).encrypt(any());
        verify(organizationRepository, never()).getReferenceById(any(UUID.class));
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
    void updateSetsReplicaFieldsAndEncryptsPassword() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        when(encryptionService.encrypt("replica-pw")).thenReturn("ENC(replica-pw)");

        service.update(datasourceId, orgId, new UpdateDatasourceCommand(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null,
                "jdbc:postgresql://replica:5432/appdb", "replica-user", "replica-pw", null));

        assertThat(entity.getReadReplicaJdbcUrl())
                .isEqualTo("jdbc:postgresql://replica:5432/appdb");
        assertThat(entity.getReadReplicaUsername()).isEqualTo("replica-user");
        assertThat(entity.getReadReplicaPasswordEncrypted()).isEqualTo("ENC(replica-pw)");
        verify(eventPublisher).publishEvent(new DatasourceConfigChangedEvent(datasourceId));
    }

    @Test
    void updateClearsReplicaWhenJdbcUrlBlank() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        entity.setReadReplicaJdbcUrl("jdbc:postgresql://replica/appdb");
        entity.setReadReplicaUsername("ru");
        entity.setReadReplicaPasswordEncrypted("ENC(rpw)");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));

        service.update(datasourceId, orgId, new UpdateDatasourceCommand(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null,
                "", null, null, null));

        assertThat(entity.getReadReplicaJdbcUrl()).isNull();
        assertThat(entity.getReadReplicaUsername()).isNull();
        assertThat(entity.getReadReplicaPasswordEncrypted()).isNull();
        verify(eventPublisher).publishEvent(new DatasourceConfigChangedEvent(datasourceId));
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
        when(permissionRepository.existsByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(false);

        assertThatThrownBy(() -> service.getForUser(datasourceId, orgId, userId))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    @Test
    void getForUserWithPermissionReturnsView() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        when(permissionRepository.existsByUser_IdAndDatasource_Id(userId, datasourceId))
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

        var command = new CreatePermissionCommand(userId, true, false, false, null, null, null,
                null, null);
        assertThatThrownBy(() -> service.grantPermission(datasourceId, orgId, adminId, command))
                .isInstanceOf(IllegalDatasourcePermissionException.class);
    }

    @Test
    void grantPermissionRejectsUnknownUser() {
        var entity = buildDatasource(datasourceId, orgId, "Prod");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(entity));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        var command = new CreatePermissionCommand(userId, true, false, false, null, null, null,
                null, null);
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

        var command = new CreatePermissionCommand(userId, true, false, false, null, null, null,
                null, null);
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

        var command = new CreatePermissionCommand(userId, true, true, false, 500,
                List.of("public"), List.of("orders"), List.of("public.orders.ssn"), null);
        var view = service.grantPermission(datasourceId, orgId, adminId, command);

        assertThat(view.userId()).isEqualTo(userId);
        assertThat(view.userEmail()).isEqualTo("alice@example.com");
        assertThat(view.canRead()).isTrue();
        assertThat(view.canWrite()).isTrue();
        assertThat(view.canDdl()).isFalse();
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
                null, null, null, null, null, true, null, null, null, null, null, null, null);

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
                null, null, null, null, null, true, aiConfigId, null, null, null, null, null, null);
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
                null, null, null, null, null, false, null, true, null, null, null, null, null);

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
                null, null, null, null, null, false, aiConfigId, true, null, null, null, null, null);
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
                null, null, null, null, null, false, null, null, customDriverId,
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
                null, null, null, null, null, false, null, null, null,
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
                null, null, null, null, null, false, null, null, customDriverId, null,
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
                null, null, null, null, null, false, null, null, customDriverId,
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
                null, null, null, null, null, false, null, null, null,
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
                null, null, null, null, null, false, null, null, customDriverId, null,
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
                null, null, null, null, null, false, null, null, customDriverId, null,
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
}
