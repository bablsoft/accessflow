package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.ConnectionTestResult;
import com.partqam.accessflow.core.api.CreateDatasourceCommand;
import com.partqam.accessflow.core.api.CreatePermissionCommand;
import com.partqam.accessflow.core.api.DatabaseSchemaView;
import com.partqam.accessflow.core.api.DatasourceAdminService;
import com.partqam.accessflow.core.api.DatasourceConnectionTestException;
import com.partqam.accessflow.core.api.DatasourceNameAlreadyExistsException;
import com.partqam.accessflow.core.api.DatasourceNotFoundException;
import com.partqam.accessflow.core.api.DatasourcePermissionAlreadyExistsException;
import com.partqam.accessflow.core.api.DatasourcePermissionNotFoundException;
import com.partqam.accessflow.core.api.DatasourcePermissionView;
import com.partqam.accessflow.core.api.DatasourceView;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.DriverCatalogService;
import com.partqam.accessflow.core.api.IllegalDatasourcePermissionException;
import com.partqam.accessflow.core.api.JdbcCoordinatesFactory;
import com.partqam.accessflow.core.api.SslMode;
import com.partqam.accessflow.core.api.UpdateDatasourceCommand;
import com.partqam.accessflow.core.events.DatasourceConfigChangedEvent;
import com.partqam.accessflow.core.events.DatasourceDeactivatedEvent;
import com.partqam.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.partqam.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.partqam.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import com.partqam.accessflow.core.api.CredentialEncryptionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DatasourceAdminServiceImpl implements DatasourceAdminService {

    private static final Logger log = LoggerFactory.getLogger(DatasourceAdminServiceImpl.class);

    private static final Set<String> POSTGRES_SYSTEM_SCHEMAS = Set.of(
            "pg_catalog", "information_schema", "pg_toast");
    private static final Set<String> MYSQL_SYSTEM_SCHEMAS = Set.of(
            "mysql", "information_schema", "performance_schema", "sys");
    private static final int CONNECTION_TIMEOUT_SECONDS = 5;

    private final DatasourceRepository datasourceRepository;
    private final DatasourceUserPermissionRepository permissionRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final ReviewPlanRepository reviewPlanRepository;
    private final CredentialEncryptionService encryptionService;
    private final JdbcCoordinatesFactory coordinatesFactory;
    private final DriverCatalogService driverCatalog;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(readOnly = true)
    public Page<DatasourceView> listForAdmin(UUID organizationId, Pageable pageable) {
        return datasourceRepository.findAllByOrganization_Id(organizationId, pageable)
                .map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DatasourceView> listForUser(UUID organizationId, UUID userId, Pageable pageable) {
        return datasourceRepository.findAllVisibleToUser(organizationId, userId, pageable)
                .map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public DatasourceView getForAdmin(UUID id, UUID organizationId) {
        return toView(loadInOrganization(id, organizationId));
    }

    @Override
    @Transactional(readOnly = true)
    public DatasourceView getForUser(UUID id, UUID organizationId, UUID userId) {
        var entity = loadInOrganization(id, organizationId);
        if (!permissionRepository.existsByUser_IdAndDatasource_Id(userId, id)) {
            throw new DatasourceNotFoundException(id);
        }
        return toView(entity);
    }

    @Override
    @Transactional
    public DatasourceView create(CreateDatasourceCommand command) {
        if (datasourceRepository.existsByOrganization_IdAndNameIgnoreCase(
                command.organizationId(), command.name())) {
            throw new DatasourceNameAlreadyExistsException(command.name());
        }
        var entity = new DatasourceEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(organizationRepository.getReferenceById(command.organizationId()));
        entity.setName(command.name());
        entity.setDbType(command.dbType());
        entity.setHost(command.host());
        entity.setPort(command.port());
        entity.setDatabaseName(command.databaseName());
        entity.setUsername(command.username());
        entity.setPasswordEncrypted(encryptionService.encrypt(command.password()));
        entity.setSslMode(command.sslMode() != null ? command.sslMode() : SslMode.DISABLE);
        if (command.connectionPoolSize() != null) {
            entity.setConnectionPoolSize(command.connectionPoolSize());
        }
        if (command.maxRowsPerQuery() != null) {
            entity.setMaxRowsPerQuery(command.maxRowsPerQuery());
        }
        if (command.requireReviewReads() != null) {
            entity.setRequireReviewReads(command.requireReviewReads());
        }
        if (command.requireReviewWrites() != null) {
            entity.setRequireReviewWrites(command.requireReviewWrites());
        }
        if (command.aiAnalysisEnabled() != null) {
            entity.setAiAnalysisEnabled(command.aiAnalysisEnabled());
        }
        if (command.reviewPlanId() != null) {
            entity.setReviewPlan(reviewPlanRepository.getReferenceById(command.reviewPlanId()));
        }
        entity.setActive(true);
        return toView(datasourceRepository.save(entity));
    }

    @Override
    @Transactional
    public DatasourceView update(UUID id, UUID organizationId, UpdateDatasourceCommand command) {
        var entity = loadInOrganization(id, organizationId);
        var before = poolFingerprint(entity);
        var wasActive = entity.isActive();
        if (command.name() != null && !command.name().equals(entity.getName())) {
            if (datasourceRepository.existsByOrganization_IdAndNameIgnoreCaseAndIdNot(
                    organizationId, command.name(), id)) {
                throw new DatasourceNameAlreadyExistsException(command.name());
            }
            entity.setName(command.name());
        }
        if (command.host() != null) {
            entity.setHost(command.host());
        }
        if (command.port() != null) {
            entity.setPort(command.port());
        }
        if (command.databaseName() != null) {
            entity.setDatabaseName(command.databaseName());
        }
        if (command.username() != null) {
            entity.setUsername(command.username());
        }
        if (command.password() != null) {
            entity.setPasswordEncrypted(encryptionService.encrypt(command.password()));
        }
        if (command.sslMode() != null) {
            entity.setSslMode(command.sslMode());
        }
        if (command.connectionPoolSize() != null) {
            entity.setConnectionPoolSize(command.connectionPoolSize());
        }
        if (command.maxRowsPerQuery() != null) {
            entity.setMaxRowsPerQuery(command.maxRowsPerQuery());
        }
        if (command.requireReviewReads() != null) {
            entity.setRequireReviewReads(command.requireReviewReads());
        }
        if (command.requireReviewWrites() != null) {
            entity.setRequireReviewWrites(command.requireReviewWrites());
        }
        if (command.aiAnalysisEnabled() != null) {
            entity.setAiAnalysisEnabled(command.aiAnalysisEnabled());
        }
        if (command.reviewPlanId() != null) {
            entity.setReviewPlan(reviewPlanRepository.getReferenceById(command.reviewPlanId()));
        }
        if (command.active() != null) {
            entity.setActive(command.active());
        }
        if (wasActive && !entity.isActive()) {
            eventPublisher.publishEvent(new DatasourceDeactivatedEvent(entity.getId()));
        } else if (!Objects.equals(before, poolFingerprint(entity))) {
            eventPublisher.publishEvent(new DatasourceConfigChangedEvent(entity.getId()));
        }
        return toView(entity);
    }

    @Override
    @Transactional
    public void deactivate(UUID id, UUID organizationId) {
        var entity = loadInOrganization(id, organizationId);
        if (!entity.isActive()) {
            return;
        }
        entity.setActive(false);
        eventPublisher.publishEvent(new DatasourceDeactivatedEvent(entity.getId()));
    }

    private static PoolFingerprint poolFingerprint(DatasourceEntity entity) {
        return new PoolFingerprint(
                entity.getHost(),
                entity.getPort(),
                entity.getDatabaseName(),
                entity.getUsername(),
                entity.getPasswordEncrypted(),
                entity.getSslMode(),
                entity.getConnectionPoolSize());
    }

    private record PoolFingerprint(String host, int port, String databaseName, String username,
                                   String passwordEncrypted, SslMode sslMode,
                                   int connectionPoolSize) {
    }

    @Override
    @Transactional(readOnly = true)
    public ConnectionTestResult test(UUID id, UUID organizationId) {
        var entity = loadInOrganization(id, organizationId);
        var resolved = driverCatalog.resolve(entity.getDbType());
        var url = buildJdbcUrl(entity);
        var props = jdbcProperties(entity);
        var start = System.currentTimeMillis();
        try (var connection = resolved.driver().connect(url, props);
             var stmt = connection.createStatement();
             var rs = stmt.executeQuery("SELECT 1")) {
            rs.next();
            var latency = System.currentTimeMillis() - start;
            return new ConnectionTestResult(true, latency, "ok");
        } catch (SQLException e) {
            log.warn("Connection test failed for datasource {}: {}", id, e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public DatabaseSchemaView introspectSchema(UUID id, UUID organizationId, UUID userId,
                                               boolean isAdmin) {
        var entity = loadInOrganization(id, organizationId);
        if (!isAdmin && !permissionRepository.existsByUser_IdAndDatasource_Id(userId, id)) {
            throw new DatasourceNotFoundException(id);
        }
        return introspect(id, entity);
    }

    @Override
    @Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public DatabaseSchemaView introspectSchemaForSystem(UUID id, UUID organizationId) {
        var entity = loadInOrganization(id, organizationId);
        return introspect(id, entity);
    }

    private DatabaseSchemaView introspect(UUID id, DatasourceEntity entity) {
        var resolved = driverCatalog.resolve(entity.getDbType());
        var url = buildJdbcUrl(entity);
        var props = jdbcProperties(entity);
        try (var connection = resolved.driver().connect(url, props)) {
            return readSchema(connection, entity.getDbType());
        } catch (SQLException e) {
            log.warn("Schema introspection failed for datasource {}: {}", id, e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<DatasourcePermissionView> listPermissions(UUID datasourceId, UUID organizationId) {
        loadInOrganization(datasourceId, organizationId);
        return permissionRepository.findAllByDatasource_Id(datasourceId).stream()
                .map(this::toPermissionView)
                .toList();
    }

    @Override
    @Transactional
    public DatasourcePermissionView grantPermission(UUID datasourceId, UUID organizationId,
                                                    UUID grantedByUserId,
                                                    CreatePermissionCommand command) {
        var datasource = loadInOrganization(datasourceId, organizationId);
        var user = userRepository.findById(command.userId())
                .orElseThrow(() -> new IllegalDatasourcePermissionException(
                        "User not found: " + command.userId()));
        if (!user.getOrganization().getId().equals(organizationId)) {
            throw new IllegalDatasourcePermissionException(
                    "User does not belong to this organization");
        }
        if (permissionRepository.existsByUser_IdAndDatasource_Id(command.userId(), datasourceId)) {
            throw new DatasourcePermissionAlreadyExistsException(command.userId(), datasourceId);
        }
        var grantedBy = userRepository.getReferenceById(grantedByUserId);
        var entity = new DatasourceUserPermissionEntity();
        entity.setId(UUID.randomUUID());
        entity.setDatasource(datasource);
        entity.setUser(user);
        entity.setCanRead(Boolean.TRUE.equals(command.canRead()));
        entity.setCanWrite(Boolean.TRUE.equals(command.canWrite()));
        entity.setCanDdl(Boolean.TRUE.equals(command.canDdl()));
        entity.setRowLimitOverride(command.rowLimitOverride());
        entity.setAllowedSchemas(toArray(command.allowedSchemas()));
        entity.setAllowedTables(toArray(command.allowedTables()));
        entity.setExpiresAt(command.expiresAt());
        entity.setCreatedBy(grantedBy);
        return toPermissionView(permissionRepository.save(entity));
    }

    @Override
    @Transactional
    public void revokePermission(UUID datasourceId, UUID organizationId, UUID permissionId) {
        loadInOrganization(datasourceId, organizationId);
        var permission = permissionRepository.findById(permissionId)
                .orElseThrow(() -> new DatasourcePermissionNotFoundException(permissionId));
        if (!permission.getDatasource().getId().equals(datasourceId)) {
            throw new DatasourcePermissionNotFoundException(permissionId);
        }
        permissionRepository.delete(permission);
    }

    private DatasourceEntity loadInOrganization(UUID id, UUID organizationId) {
        var entity = datasourceRepository.findById(id)
                .orElseThrow(() -> new DatasourceNotFoundException(id));
        if (!entity.getOrganization().getId().equals(organizationId)) {
            throw new DatasourceNotFoundException(id);
        }
        return entity;
    }

    private DatasourceView toView(DatasourceEntity entity) {
        return new DatasourceView(
                entity.getId(),
                entity.getOrganization().getId(),
                entity.getName(),
                entity.getDbType(),
                entity.getHost(),
                entity.getPort(),
                entity.getDatabaseName(),
                entity.getUsername(),
                entity.getSslMode(),
                entity.getConnectionPoolSize(),
                entity.getMaxRowsPerQuery(),
                entity.isRequireReviewReads(),
                entity.isRequireReviewWrites(),
                entity.getReviewPlan() != null ? entity.getReviewPlan().getId() : null,
                entity.isAiAnalysisEnabled(),
                entity.isActive(),
                entity.getCreatedAt());
    }

    private DatasourcePermissionView toPermissionView(DatasourceUserPermissionEntity entity) {
        UserEntity user = entity.getUser();
        return new DatasourcePermissionView(
                entity.getId(),
                entity.getDatasource().getId(),
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                entity.isCanRead(),
                entity.isCanWrite(),
                entity.isCanDdl(),
                entity.getRowLimitOverride(),
                toList(entity.getAllowedSchemas()),
                toList(entity.getAllowedTables()),
                entity.getExpiresAt(),
                entity.getCreatedBy() != null ? entity.getCreatedBy().getId() : null,
                entity.getCreatedAt());
    }

    private String buildJdbcUrl(DatasourceEntity entity) {
        return coordinatesFactory.from(
                entity.getDbType(), entity.getHost(), entity.getPort(),
                entity.getDatabaseName(), entity.getUsername(), entity.getSslMode()).url();
    }

    private Properties jdbcProperties(DatasourceEntity entity) {
        var props = new Properties();
        props.setProperty("user", entity.getUsername());
        props.setProperty("password", encryptionService.decrypt(entity.getPasswordEncrypted()));
        if (entity.getDbType() == DbType.POSTGRESQL) {
            props.setProperty("connectTimeout", String.valueOf(CONNECTION_TIMEOUT_SECONDS));
            props.setProperty("socketTimeout", String.valueOf(CONNECTION_TIMEOUT_SECONDS));
        } else if (entity.getDbType() == DbType.MYSQL) {
            props.setProperty("connectTimeout", String.valueOf(CONNECTION_TIMEOUT_SECONDS * 1000));
            props.setProperty("socketTimeout", String.valueOf(CONNECTION_TIMEOUT_SECONDS * 1000));
        }
        return props;
    }

    private DatabaseSchemaView readSchema(Connection connection, DbType dbType) throws SQLException {
        DatabaseMetaData md = connection.getMetaData();
        Set<String> systemSchemas = dbType == DbType.POSTGRESQL
                ? POSTGRES_SYSTEM_SCHEMAS : MYSQL_SYSTEM_SCHEMAS;
        Map<String, Map<String, List<DatabaseSchemaView.Column>>> grouped = new LinkedHashMap<>();
        try (ResultSet tables = md.getTables(connection.getCatalog(), null, "%",
                new String[]{"TABLE"})) {
            while (tables.next()) {
                String schemaName = tables.getString("TABLE_SCHEM");
                if (dbType == DbType.MYSQL && schemaName == null) {
                    schemaName = tables.getString("TABLE_CAT");
                }
                if (schemaName == null || systemSchemas.contains(schemaName)) {
                    continue;
                }
                String tableName = tables.getString("TABLE_NAME");
                Set<String> primaryKeys = readPrimaryKeys(md, connection.getCatalog(), schemaName,
                        tableName);
                List<DatabaseSchemaView.Column> columns = readColumns(md, connection.getCatalog(),
                        schemaName, tableName, primaryKeys);
                grouped.computeIfAbsent(schemaName, k -> new LinkedHashMap<>())
                        .put(tableName, columns);
            }
        }
        List<DatabaseSchemaView.Schema> schemaList = new ArrayList<>();
        grouped.forEach((schemaName, tableMap) -> {
            List<DatabaseSchemaView.Table> tableViews = new ArrayList<>();
            tableMap.forEach((tableName, cols) ->
                    tableViews.add(new DatabaseSchemaView.Table(tableName, cols)));
            schemaList.add(new DatabaseSchemaView.Schema(schemaName, tableViews));
        });
        return new DatabaseSchemaView(schemaList);
    }

    private Set<String> readPrimaryKeys(DatabaseMetaData md, String catalog, String schema,
                                        String table) throws SQLException {
        var keys = new java.util.HashSet<String>();
        try (ResultSet pk = md.getPrimaryKeys(catalog, schema, table)) {
            while (pk.next()) {
                keys.add(pk.getString("COLUMN_NAME"));
            }
        }
        return keys;
    }

    private List<DatabaseSchemaView.Column> readColumns(DatabaseMetaData md, String catalog,
                                                        String schema, String table,
                                                        Set<String> primaryKeys)
            throws SQLException {
        var columns = new ArrayList<DatabaseSchemaView.Column>();
        try (ResultSet cols = md.getColumns(catalog, schema, table, "%")) {
            while (cols.next()) {
                String name = cols.getString("COLUMN_NAME");
                String type = cols.getString("TYPE_NAME");
                boolean nullable = cols.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                columns.add(new DatabaseSchemaView.Column(
                        name, type, nullable, primaryKeys.contains(name)));
            }
        }
        return columns;
    }

    private static String[] toArray(List<String> values) {
        return values == null ? null : values.toArray(new String[0]);
    }

    private static List<String> toList(String[] values) {
        return values == null ? null : List.of(values);
    }
}
