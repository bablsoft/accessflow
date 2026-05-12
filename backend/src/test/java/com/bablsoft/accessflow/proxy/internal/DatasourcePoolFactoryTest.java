package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DriverCatalogService;
import com.bablsoft.accessflow.core.api.JdbcCoordinates;
import com.bablsoft.accessflow.core.api.JdbcCoordinatesFactory;
import com.bablsoft.accessflow.core.api.ResolvedDriver;
import com.bablsoft.accessflow.core.api.SslMode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.sql.Driver;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DatasourcePoolFactoryTest {

    private CredentialEncryptionService encryptionService;
    private JdbcCoordinatesFactory coordinatesFactory;
    private ProxyPoolProperties properties;
    private DriverCatalogService driverCatalog;
    private com.bablsoft.accessflow.core.api.CustomJdbcDriverService customJdbcDriverService;
    private ClassLoader perTypeClassLoader;
    private DatasourcePoolFactory factory;

    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final DatasourceConnectionDescriptor descriptor = new DatasourceConnectionDescriptor(
            datasourceId, organizationId, DbType.POSTGRESQL, "h", 5432, "appdb", "svc",
            "ENC(secret)", SslMode.DISABLE, 15, 1000, false, null, null, null, true);

    @BeforeEach
    void setUp() {
        encryptionService = mock(CredentialEncryptionService.class);
        coordinatesFactory = mock(JdbcCoordinatesFactory.class);
        driverCatalog = mock(DriverCatalogService.class);
        customJdbcDriverService = mock(com.bablsoft.accessflow.core.api.CustomJdbcDriverService.class);
        properties = new ProxyPoolProperties(
                Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMinutes(30),
                Duration.ZERO, "accessflow-ds-", null);
        when(coordinatesFactory.from(any(), anyString(), anyInt(), anyString(), anyString(), any()))
                .thenReturn(new JdbcCoordinates(
                        "jdbc:postgresql://h:5432/appdb?sslmode=disable",
                        "org.postgresql.Driver", "svc"));
        when(encryptionService.decrypt("ENC(secret)")).thenReturn("plaintext");
        perTypeClassLoader = new ClassLoader(getClass().getClassLoader()) {};
        when(driverCatalog.resolve(DbType.POSTGRESQL))
                .thenReturn(new ResolvedDriver(mock(Driver.class), perTypeClassLoader,
                        "org.postgresql.Driver"));
        factory = new DatasourcePoolFactory(encryptionService, coordinatesFactory, properties,
                driverCatalog, customJdbcDriverService);
    }

    @Test
    void createPoolWiresAllHikariConfigFromDescriptorAndProperties() {
        var captured = new AtomicReference<HikariConfig>();
        try (MockedConstruction<HikariDataSource> mocked = Mockito.mockConstruction(
                HikariDataSource.class,
                (mock, ctx) -> captured.set((HikariConfig) ctx.arguments().get(0)))) {

            factory.createPool(descriptor);

            assertThat(mocked.constructed()).hasSize(1);
            var config = captured.get();
            assertThat(config.getJdbcUrl()).isEqualTo(
                    "jdbc:postgresql://h:5432/appdb?sslmode=disable");
            assertThat(config.getDriverClassName()).isEqualTo("org.postgresql.Driver");
            assertThat(config.getUsername()).isEqualTo("svc");
            assertThat(config.getPassword()).isEqualTo("plaintext");
            assertThat(config.getMaximumPoolSize()).isEqualTo(15);
            assertThat(config.getConnectionTimeout()).isEqualTo(30_000L);
            assertThat(config.getIdleTimeout()).isEqualTo(600_000L);
            assertThat(config.getMaxLifetime()).isEqualTo(1_800_000L);
            assertThat(config.getPoolName()).isEqualTo("accessflow-ds-" + datasourceId);
        }
    }

    @Test
    void createPoolDecryptsPasswordExactlyOnce() {
        try (MockedConstruction<HikariDataSource> ignored = Mockito.mockConstruction(
                HikariDataSource.class)) {

            factory.createPool(descriptor);

            verify(encryptionService, times(1)).decrypt("ENC(secret)");
        }
    }

    @Test
    void createPoolSkipsLeakDetectionWhenZero() {
        var captured = new AtomicReference<HikariConfig>();
        try (MockedConstruction<HikariDataSource> ignored = Mockito.mockConstruction(
                HikariDataSource.class,
                (mock, ctx) -> captured.set((HikariConfig) ctx.arguments().get(0)))) {

            factory.createPool(descriptor);

            assertThat(captured.get().getLeakDetectionThreshold()).isEqualTo(0);
        }
    }

    @Test
    void createPoolSetsLeakDetectionWhenPositive() {
        properties = new ProxyPoolProperties(
                Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMinutes(30),
                Duration.ofSeconds(2), "accessflow-ds-", null);
        factory = new DatasourcePoolFactory(encryptionService, coordinatesFactory, properties,
                driverCatalog, customJdbcDriverService);

        var captured = new AtomicReference<HikariConfig>();
        try (MockedConstruction<HikariDataSource> ignored = Mockito.mockConstruction(
                HikariDataSource.class,
                (mock, ctx) -> captured.set((HikariConfig) ctx.arguments().get(0)))) {

            factory.createPool(descriptor);

            assertThat(captured.get().getLeakDetectionThreshold()).isEqualTo(2_000L);
        }
    }

    @Test
    void createPoolSwapsContextClassLoaderForHikariInstantiation() {
        var observed = new AtomicReference<ClassLoader>();
        try (MockedConstruction<HikariDataSource> ignored = Mockito.mockConstruction(
                HikariDataSource.class,
                (mock, ctx) -> observed.set(Thread.currentThread().getContextClassLoader()))) {

            var prior = Thread.currentThread().getContextClassLoader();

            factory.createPool(descriptor);

            assertThat(observed.get()).isSameAs(perTypeClassLoader);
            assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(prior);
        }
    }

    @Test
    void createPoolWithCustomDriverIdUsesPerDriverClassloaderAndDriverClass() {
        var customDriverId = UUID.randomUUID();
        var customDescriptor = new DatasourceConnectionDescriptor(
                datasourceId, organizationId, DbType.POSTGRESQL, "h", 5432, "appdb", "svc",
                "ENC(secret)", SslMode.DISABLE, 15, 1000, false, null, customDriverId, null, true);
        // HikariConfig.setDriverClassName() eagerly verifies the class is loadable, so we use
        // a real class name on the test classpath. The classloader-swap assertion still
        // demonstrates that the custom path is taken.
        var customLoader = new ClassLoader(getClass().getClassLoader()) {};
        var driverDescriptor = new com.bablsoft.accessflow.core.api.CustomDriverDescriptor(
                customDriverId, organizationId, DbType.POSTGRESQL, "Acme",
                "org.postgresql.Driver", "driver.jar", "a".repeat(64), 1024, "custom/x.jar");
        when(customJdbcDriverService.findById(customDriverId, organizationId))
                .thenReturn(java.util.Optional.of(driverDescriptor));
        when(driverCatalog.resolveCustom(driverDescriptor))
                .thenReturn(new ResolvedDriver(mock(Driver.class), customLoader,
                        "org.postgresql.Driver"));

        var observed = new AtomicReference<ClassLoader>();
        var captured = new AtomicReference<HikariConfig>();
        try (MockedConstruction<HikariDataSource> mocked = Mockito.mockConstruction(
                HikariDataSource.class,
                (mock, ctx) -> {
                    captured.set((HikariConfig) ctx.arguments().get(0));
                    observed.set(Thread.currentThread().getContextClassLoader());
                })) {

            factory.createPool(customDescriptor);

            assertThat(observed.get()).isSameAs(customLoader);
            assertThat(captured.get().getDriverClassName()).isEqualTo("org.postgresql.Driver");
            // Bundled driver catalog must NOT have been consulted on the custom path.
            verify(driverCatalog, times(0)).resolve(any());
        }
    }

    @Test
    void createPoolWithCustomDriverIdThrowsCustomDriverNotFoundWhenLookupEmpty() {
        var customDriverId = UUID.randomUUID();
        var customDescriptor = new DatasourceConnectionDescriptor(
                datasourceId, organizationId, DbType.POSTGRESQL, "h", 5432, "appdb", "svc",
                "ENC(secret)", SslMode.DISABLE, 15, 1000, false, null, customDriverId, null, true);
        when(customJdbcDriverService.findById(customDriverId, organizationId))
                .thenReturn(java.util.Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> factory.createPool(customDescriptor))
                .isInstanceOf(com.bablsoft.accessflow.core.api.CustomDriverNotFoundException.class)
                .satisfies(ex -> assertThat(
                        ((com.bablsoft.accessflow.core.api.CustomDriverNotFoundException) ex).driverId())
                        .isEqualTo(customDriverId));
    }

    @Test
    void createPoolWithJdbcUrlOverrideBypassesCoordinatesFactory() {
        var overrideUrl = "jdbc:snowflake://acme.snowflakecomputing.com/?db=PROD";
        var dynamicDescriptor = new DatasourceConnectionDescriptor(
                datasourceId, organizationId, DbType.POSTGRESQL,
                null, null, null, "svc",
                "ENC(secret)", SslMode.DISABLE, 15, 1000, false, null, null, overrideUrl, true);

        var captured = new AtomicReference<HikariConfig>();
        try (MockedConstruction<HikariDataSource> ignored = Mockito.mockConstruction(
                HikariDataSource.class,
                (mock, ctx) -> captured.set((HikariConfig) ctx.arguments().get(0)))) {

            factory.createPool(dynamicDescriptor);

            assertThat(captured.get().getJdbcUrl()).isEqualTo(overrideUrl);
            // CoordinatesFactory must not be consulted when an override is present.
            verify(coordinatesFactory, times(0)).from(any(), anyString(), anyInt(),
                    anyString(), anyString(), any());
        }
    }
}
