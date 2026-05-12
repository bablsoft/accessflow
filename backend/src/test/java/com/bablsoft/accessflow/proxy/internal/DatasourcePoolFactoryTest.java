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
class DatasourcePoolFactoryTest {

    private CredentialEncryptionService encryptionService;
    private JdbcCoordinatesFactory coordinatesFactory;
    private ProxyPoolProperties properties;
    private DriverCatalogService driverCatalog;
    private ClassLoader perTypeClassLoader;
    private DatasourcePoolFactory factory;

    private final UUID datasourceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final DatasourceConnectionDescriptor descriptor = new DatasourceConnectionDescriptor(
            datasourceId, organizationId, DbType.POSTGRESQL, "h", 5432, "appdb", "svc",
            "ENC(secret)", SslMode.DISABLE, 15, 1000, false, null, true);

    @BeforeEach
    void setUp() {
        encryptionService = mock(CredentialEncryptionService.class);
        coordinatesFactory = mock(JdbcCoordinatesFactory.class);
        driverCatalog = mock(DriverCatalogService.class);
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
                driverCatalog);
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
                driverCatalog);

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
}
