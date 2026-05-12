package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.ConnectionTestResult;
import com.bablsoft.accessflow.core.api.CreateDatasourceCommand;
import com.bablsoft.accessflow.core.api.CreatePermissionCommand;
import com.bablsoft.accessflow.core.api.CustomDriverNotFoundException;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import com.bablsoft.accessflow.core.api.DatasourceNameAlreadyExistsException;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourcePermissionAlreadyExistsException;
import com.bablsoft.accessflow.core.api.DatasourcePermissionNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourcePermissionView;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverCatalogService;
import com.bablsoft.accessflow.core.api.IllegalDatasourcePermissionException;
import com.bablsoft.accessflow.core.api.JdbcCoordinatesFactory;
import com.bablsoft.accessflow.core.api.MissingAiConfigForDatasourceException;
import com.bablsoft.accessflow.core.api.ResolvedDriver;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UpdateDatasourceCommand;
import com.bablsoft.accessflow.core.internal.persistence.repo.CustomJdbcDriverRepository;
import com.bablsoft.accessflow.core.internal.persistence.entity.CustomJdbcDriverEntity;
import com.bablsoft.accessflow.core.events.DatasourceConfigChangedEvent;
import com.bablsoft.accessflow.core.events.DatasourceDeactivatedEvent;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
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
    private final CustomJdbcDriverRepository customJdbcDriverRepository;
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
        var customDriver = resolveCustomDriverForCreate(command);
        validateDriverChoice(command.dbType(), command.customDriverId(), customDriver,
                command.jdbcUrlOverride(), command.host(), command.port(), command.databaseName());
        if (customDriver == null) {
            // Fail-fast for bundled drivers (mirrors pre-#94 behaviour). Custom drivers are
            // probe-loaded at upload time, so we trust the catalog cache here.
            driverCatalog.resolve(command.dbType());
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
        entity.setCustomDriver(customDriver);
        entity.setJdbcUrlOverride(command.jdbcUrlOverride());
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
        if (command.aiConfigId() != null) {
            entity.setAiConfigId(command.aiConfigId());
        }
        if (entity.isAiAnalysisEnabled() && entity.getAiConfigId() == null) {
            throw new MissingAiConfigForDatasourceException();
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
        if (Boolean.TRUE.equals(command.clearAiConfig())) {
            entity.setAiConfigId(null);
        }
        if (command.aiConfigId() != null) {
            entity.setAiConfigId(command.aiConfigId());
        }
        if (entity.isAiAnalysisEnabled() && entity.getAiConfigId() == null) {
            throw new MissingAiConfigForDatasourceException();
        }
        if (command.jdbcUrlOverride() != null) {
            entity.setJdbcUrlOverride(command.jdbcUrlOverride().isBlank()
                    ? null : command.jdbcUrlOverride());
        }
        validateDriverChoice(entity.getDbType(),
                entity.getCustomDriver() != null ? entity.getCustomDriver().getId() : null,
                entity.getCustomDriver(),
                entity.getJdbcUrlOverride(),
                entity.getHost(),
                entity.getPort(),
                entity.getDatabaseName());
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
                entity.getConnectionPoolSize(),
                entity.getCustomDriver() != null ? entity.getCustomDriver().getId() : null,
                entity.getJdbcUrlOverride());
    }

    private record PoolFingerprint(String host, Integer port, String databaseName, String username,
                                   String passwordEncrypted, SslMode sslMode,
                                   int connectionPoolSize, UUID customDriverId,
                                   String jdbcUrlOverride) {
    }

    @Override
    @Transactional(readOnly = true)
    public ConnectionTestResult test(UUID id, UUID organizationId) {
        var entity = loadInOrganization(id, organizationId);
        var resolved = resolveDriver(entity);
        var url = buildJdbcUrl(entity);
        var props = jdbcProperties(entity);
        var start = System.currentTimeMillis();
        try (var connection = resolved.driver().connect(url, props);
             var stmt = connection.createStatement();
             var rs = stmt.executeQuery(probeSql(entity.getDbType()))) {
            rs.next();
            var latency = System.currentTimeMillis() - start;
            return new ConnectionTestResult(true, latency, "ok");
        } catch (SQLException e) {
            log.warn("Connection test failed for datasource {}: {}", id, e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        }
    }

    private ResolvedDriver resolveDriver(DatasourceEntity entity) {
        if (entity.getCustomDriver() != null) {
            var c = entity.getCustomDriver();
            return driverCatalog.resolveCustom(new com.bablsoft.accessflow.core.api.CustomDriverDescriptor(
                    c.getId(),
                    c.getOrganization().getId(),
                    c.getTargetDbType(),
                    c.getVendorName(),
                    c.getDriverClass(),
                    c.getJarFilename(),
                    c.getJarSha256(),
                    c.getJarSizeBytes(),
                    c.getStoragePath()));
        }
        return driverCatalog.resolve(entity.getDbType());
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
        var resolved = resolveDriver(entity);
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
        entity.setRestrictedColumns(toArray(command.restrictedColumns()));
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
                entity.getAiConfigId(),
                entity.getCustomDriver() != null ? entity.getCustomDriver().getId() : null,
                entity.getJdbcUrlOverride(),
                entity.isActive(),
                entity.getCreatedAt());
    }

    private CustomJdbcDriverEntity resolveCustomDriverForCreate(CreateDatasourceCommand command) {
        if (command.customDriverId() == null) {
            return null;
        }
        var driver = customJdbcDriverRepository
                .findByIdAndOrganization_Id(command.customDriverId(), command.organizationId())
                .orElseThrow(() -> new CustomDriverNotFoundException(command.customDriverId()));
        return driver;
    }

    /**
     * Enforces the connection-shape invariants that the JPA constraints can't:
     * <ul>
     *   <li>{@link DbType#CUSTOM} requires both {@code customDriverId} and a non-blank
     *       {@code jdbcUrlOverride}; host/port/databaseName must be absent.</li>
     *   <li>For bundled {@link DbType}s, host/port/databaseName are required and
     *       {@code jdbcUrlOverride} must be absent.</li>
     *   <li>If a custom driver is referenced, its {@code target_db_type} must equal the
     *       datasource's {@code db_type} or be {@code CUSTOM}.</li>
     * </ul>
     */
    private void validateDriverChoice(DbType dbType, java.util.UUID customDriverId,
                                      CustomJdbcDriverEntity customDriver, String jdbcUrlOverride,
                                      String host, Integer port, String databaseName) {
        boolean hasOverride = jdbcUrlOverride != null && !jdbcUrlOverride.isBlank();
        if (dbType == DbType.CUSTOM) {
            if (customDriverId == null) {
                throw new IllegalDatasourcePermissionException(
                        "CUSTOM datasources require a custom_driver_id");
            }
            if (!hasOverride) {
                throw new IllegalDatasourcePermissionException(
                        "CUSTOM datasources require a jdbc_url_override");
            }
            if (host != null || port != null || (databaseName != null && !databaseName.isBlank())) {
                throw new IllegalDatasourcePermissionException(
                        "CUSTOM datasources must not set host/port/database_name");
            }
        } else {
            if (host == null || host.isBlank()) {
                throw new IllegalDatasourcePermissionException(
                        "Datasource host is required for bundled db_type " + dbType);
            }
            if (port == null) {
                throw new IllegalDatasourcePermissionException(
                        "Datasource port is required for bundled db_type " + dbType);
            }
            if (databaseName == null || databaseName.isBlank()) {
                throw new IllegalDatasourcePermissionException(
                        "Datasource database_name is required for bundled db_type " + dbType);
            }
            if (hasOverride) {
                throw new IllegalDatasourcePermissionException(
                        "jdbc_url_override is only allowed when db_type is CUSTOM");
            }
        }
        if (customDriver != null && customDriver.getTargetDbType() != DbType.CUSTOM
                && customDriver.getTargetDbType() != dbType) {
            throw new IllegalDatasourcePermissionException(
                    "Custom driver target_db_type " + customDriver.getTargetDbType()
                            + " does not match datasource db_type " + dbType);
        }
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
                toList(entity.getRestrictedColumns()),
                entity.getExpiresAt(),
                entity.getCreatedBy() != null ? entity.getCreatedBy().getId() : null,
                entity.getCreatedAt());
    }

    static String probeSql(DbType dbType) {
        // Oracle rejects bare SELECT 1 with ORA-00923 — every SELECT must have a FROM.
        // CUSTOM datasources use a portable bare SELECT 1; admins targeting Oracle-flavoured
        // engines without using db_type=ORACLE need to ensure that works in their dialect.
        return dbType == DbType.ORACLE ? "SELECT 1 FROM DUAL" : "SELECT 1";
    }

    private String buildJdbcUrl(DatasourceEntity entity) {
        if (entity.getJdbcUrlOverride() != null && !entity.getJdbcUrlOverride().isBlank()) {
            return entity.getJdbcUrlOverride();
        }
        return coordinatesFactory.from(
                entity.getDbType(), entity.getHost(),
                entity.getPort() != null ? entity.getPort() : 0,
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
        // CUSTOM dialects can't be statically classified — use the Postgres allowlist as the
        // sensible default (it's the AccessFlow internal DB's flavour). Admins can refine via
        // permissions if needed.
        Set<String> systemSchemas = switch (dbType) {
            case MYSQL -> MYSQL_SYSTEM_SCHEMAS;
            case POSTGRESQL -> POSTGRES_SYSTEM_SCHEMAS;
            default -> POSTGRES_SYSTEM_SCHEMAS;
        };
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
