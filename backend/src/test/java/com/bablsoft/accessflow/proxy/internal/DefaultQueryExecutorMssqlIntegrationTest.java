package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.proxy.api.DatasourceConnectionPoolManager;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL Server dry-run coverage (issue AF-762). SHOWPLAN is unreachable over {@code mssql-jdbc}'s
 * prepared/RPC path, so {@code SqlServerDryRunPlanner} plans over a plain {@code Statement} and
 * degrades to <em>unsupported</em> when the row-security rewrite produced binds. These tests pin
 * both branches — and the SHOWPLAN non-execution guarantee — against a real SQL Server 2022.
 *
 * <p>{@code mcr.microsoft.com/mssql/server} publishes an amd64-only manifest, so the whole class
 * is skipped on arm64 hosts and normally gets its result from CI's {@code ubuntu-latest} runner.
 * To run it on an arm64 machine with Docker emulation:
 * <pre>{@code
 * DOCKER_DEFAULT_PLATFORM=linux/amd64 mvn test \
 *     -Dtest=DefaultQueryExecutorMssqlIntegrationTest \
 *     -DargLine="-Xmx6g -Dos.arch=amd64"
 * }</pre>
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64",
        disabledReason = "SQL Server container images are published for amd64 only")
class DefaultQueryExecutorMssqlIntegrationTest {

    private static final String SA_PASSWORD = "Af762-Str0ng!Pw";

    @SuppressWarnings("resource")
    static MSSQLServerContainer customerDb =
            new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
                    .acceptLicense()
                    .withPassword(SA_PASSWORD);

    @Autowired QueryExecutor executor;
    @Autowired DatasourceConnectionPoolManager poolManager;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired CredentialEncryptionService encryptionService;

    private OrganizationEntity org;
    private DatasourceEntity datasource;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
        var cacheDir = com.bablsoft.accessflow.proxy.internal.driver
                .DriverCacheTestSupport.prepareCacheWithMssql();
        registry.add("accessflow.drivers.cache-dir", cacheDir::toString);
    }

    @BeforeAll
    static void startCustomerDb() {
        customerDb.start();
    }

    @AfterAll
    static void stopCustomerDb() {
        customerDb.stop();
    }

    @BeforeEach
    void setUp() throws Exception {
        permissionRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary");
        organizationRepository.save(org);
        datasource = saveDatasource();

        try (var connection = DriverManager.getConnection(customerDb.getJdbcUrl(),
                customerDb.getUsername(), customerDb.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("IF OBJECT_ID('dbo.rich', 'U') IS NOT NULL DROP TABLE dbo.rich");
            statement.execute("""
                    CREATE TABLE dbo.rich (
                        id     INT PRIMARY KEY,
                        region VARCHAR(16) NOT NULL,
                        qty    INT NOT NULL
                    )""");
            statement.execute(
                    "INSERT INTO dbo.rich VALUES (1, 'EU', 42), (2, 'EU', 43), (3, 'US', 44)");
        }
    }

    @AfterEach
    void cleanup() {
        if (datasource != null) {
            poolManager.evict(datasource.getId());
        }
        permissionRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void dryRunSelectReturnsShowplanTree() {
        var result = executor.dryRun(new QueryExecutionRequest(
                datasource.getId(), "SELECT qty FROM dbo.rich WHERE qty > 10",
                QueryType.SELECT, null, null));

        assertThat(result.supported()).isTrue();
        assertThat(result.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(result.plan()).isNotNull();
        assertThat(result.plan().operation()).isNotBlank();
        assertThat(result.estimatedRows()).isNotNull();
    }

    @Test
    void dryRunUpdateDoesNotMutateSqlServerData() {
        var result = executor.dryRun(new QueryExecutionRequest(
                datasource.getId(), "UPDATE dbo.rich SET qty = 999", QueryType.UPDATE, null, null));
        assertThat(result.supported()).isTrue();
        assertThat(result.plan()).isNotNull();

        // SHOWPLAN_ALL plans the statement instead of running it — no row may have changed.
        var check = (SelectExecutionResult) executor.execute(new QueryExecutionRequest(
                datasource.getId(), "SELECT count(*) FROM dbo.rich WHERE qty = 999",
                QueryType.SELECT, null, null));
        assertThat(((Number) check.rows().getFirst().getFirst()).longValue()).isZero();
    }

    @Test
    void dryRunDeleteDoesNotMutateSqlServerData() {
        var result = executor.dryRun(new QueryExecutionRequest(
                datasource.getId(), "DELETE FROM dbo.rich", QueryType.DELETE, null, null));
        assertThat(result.supported()).isTrue();

        var check = (SelectExecutionResult) executor.execute(new QueryExecutionRequest(
                datasource.getId(), "SELECT count(*) FROM dbo.rich",
                QueryType.SELECT, null, null));
        assertThat(((Number) check.rows().getFirst().getFirst()).longValue()).isEqualTo(3L);
    }

    @Test
    void dryRunDdlDoesNotChangeSchema() {
        // The plain-Statement carve-out is only safe because SHOWPLAN suppresses execution for
        // every statement class the dry-run path can classify, not just DML. Pin that for DDL:
        // a CREATE that would succeed and a DROP that would succeed must both leave schema alone.
        var created = executor.dryRun(new QueryExecutionRequest(
                datasource.getId(), "CREATE TABLE dbo.af762_ddl_probe (id INT)",
                QueryType.DDL, null, null));
        assertThat(created.supported()).isTrue();
        assertThat(objectExists("dbo.af762_ddl_probe")).isZero();

        var dropped = executor.dryRun(new QueryExecutionRequest(
                datasource.getId(), "DROP TABLE dbo.rich", QueryType.DDL, null, null));
        assertThat(dropped.supported()).isTrue();
        assertThat(objectExists("dbo.rich")).isEqualTo(1L);
    }

    private long objectExists(String qualifiedName) {
        var result = (SelectExecutionResult) executor.execute(new QueryExecutionRequest(
                datasource.getId(),
                "SELECT count(*) FROM sys.objects WHERE object_id = OBJECT_ID('"
                        + qualifiedName + "')",
                QueryType.SELECT, null, null));
        return ((Number) result.rows().getFirst().getFirst()).longValue();
    }

    @Test
    void dryRunUnderRowSecurityDegradesInsteadOfFailing() {
        // The rewrite puts 'EU' behind a JdbcParameter, which SHOWPLAN cannot accept — the planner
        // must return a degraded result rather than surfacing the driver's SQLException.
        var directive = new RowSecurityDirective(UUID.randomUUID(), "dbo.rich", "region",
                RowSecurityOperator.EQUALS, List.of("EU"));

        var result = executor.dryRun(new QueryExecutionRequest(
                datasource.getId(), "SELECT qty FROM dbo.rich", QueryType.SELECT, null, null,
                List.of(), List.of(), List.of(directive), false, null, List.of()));

        assertThat(result.supported()).isFalse();
        assertThat(result.engineId()).isEqualTo("mssql");
        assertThat(result.unsupportedReason()).isNotBlank();
    }

    @Test
    void executeStillWorksUnderRowSecurity() {
        // The degradation is dry-run-only: real execution binds the same predicate normally.
        var directive = new RowSecurityDirective(UUID.randomUUID(), "dbo.rich", "region",
                RowSecurityOperator.EQUALS, List.of("EU"));

        var result = (SelectExecutionResult) executor.execute(new QueryExecutionRequest(
                datasource.getId(), "SELECT qty FROM dbo.rich", QueryType.SELECT, null, null,
                List.of(), List.of(), List.of(directive), false, null, List.of()));

        assertThat(result.rowCount()).isEqualTo(2);
    }

    private DatasourceEntity saveDatasource() {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
        ds.setName("Customer-" + UUID.randomUUID());
        ds.setDbType(DbType.MSSQL);
        ds.setHost(customerDb.getHost());
        ds.setPort(customerDb.getMappedPort(MSSQLServerContainer.MS_SQL_SERVER_PORT));
        ds.setDatabaseName("master");
        ds.setUsername(customerDb.getUsername());
        ds.setPasswordEncrypted(encryptionService.encrypt(customerDb.getPassword()));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(3);
        ds.setMaxRowsPerQuery(1000);
        ds.setRequireReviewReads(false);
        ds.setRequireReviewWrites(true);
        ds.setAiAnalysisEnabled(false);
        ds.setActive(true);
        return datasourceRepository.save(ds);
    }
}
