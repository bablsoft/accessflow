package com.bablsoft.accessflow.sqlreview.internal.persistence.repo;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.QuerySqlReviewFindingEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the V170 shape of {@code query_sql_review_findings}: the enum/JSONB mappings, the
 * statement-then-line ordering, the datasource {@code environment} column, and the
 * {@code ON DELETE CASCADE} from {@code query_requests}.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class QuerySqlReviewFindingRepositoryIntegrationTest {

    @Autowired QuerySqlReviewFindingRepository findingRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired PlatformTransactionManager transactionManager;

    private OrganizationEntity organization;
    private UserEntity submitter;
    private DatasourceEntity datasource;
    private QueryRequestEntity query;

    @BeforeEach
    void setUp() {
        organization = organizationRepository.save(newOrg());
        submitter = userRepository.save(newUser());
        datasource = datasourceRepository.save(newDatasource());
        query = queryRequestRepository.save(newQuery());
    }

    @AfterEach
    void cleanup() {
        // Only this class's rows, in reverse FK order (users do not cascade from organizations).
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                queryRequestRepository.findAllByDatasource_Id(datasource.getId(), Pageable.unpaged())
                        .forEach(queryRequestRepository::delete));
        datasourceRepository.deleteById(datasource.getId());
        userRepository.deleteById(submitter.getId());
        organizationRepository.deleteById(organization.getId());
    }

    @Test
    void persistsEnumAndJsonAndReadsBackInStatementThenLineOrder() {
        findingRepository.saveAndFlush(newFinding("order_by_without_limit", 1, 9, null));
        findingRepository.saveAndFlush(newFinding("select_star", 0, 3, "{\"table\": \"users\"}"));
        findingRepository.saveAndFlush(newFinding("missing_limit_on_select", 0, 1, null));

        var findings = findingRepository
                .findAllByQueryRequestIdOrderByStatementIndexAscLineNumberAsc(query.getId());

        assertThat(findings).extracting(QuerySqlReviewFindingEntity::getRuleId)
                .containsExactly("missing_limit_on_select", "select_star", "order_by_without_limit");
        assertThat(findings.get(1).getSeverity()).isEqualTo(SqlReviewSeverity.WARN);
        assertThat(findings.get(1).getArgs()).contains("users");
        assertThat(findings.get(0).getArgs()).isNull();
    }

    @Test
    void datasourceEnvironmentRoundTripsAndDefaultsToNull() {
        assertThat(datasourceRepository.findById(datasource.getId()).orElseThrow().getEnvironment()).isNull();

        datasource.setEnvironment(DatasourceEnvironment.STAGING);
        datasourceRepository.saveAndFlush(datasource);

        assertThat(datasourceRepository.findById(datasource.getId()).orElseThrow().getEnvironment())
                .isEqualTo(DatasourceEnvironment.STAGING);
    }

    @Test
    void deletingTheQueryRequestCascadesItsFindings() {
        findingRepository.saveAndFlush(newFinding("truncate_statement", 0, null, null));
        assertThat(findingRepository.findAllByQueryRequestIdOrderByStatementIndexAscLineNumberAsc(query.getId()))
                .hasSize(1);

        // Re-read right before deleting: query_requests carries a @Version Instant and Postgres
        // truncates to microseconds, so a pre-loaded instance mismatches on Linux.
        var fresh = queryRequestRepository.findById(query.getId()).orElseThrow();
        queryRequestRepository.delete(fresh);
        queryRequestRepository.flush();

        assertThat(findingRepository.findAllByQueryRequestIdOrderByStatementIndexAscLineNumberAsc(query.getId()))
                .isEmpty();
    }

    @Test
    void deleteAllByQueryRequestIdRemovesOnlyThatQuerysFindings() {
        var otherQuery = queryRequestRepository.save(newQuery());
        findingRepository.saveAndFlush(newFinding("select_star", 0, null, null));
        var kept = newFinding("select_star", 0, null, null);
        kept.setQueryRequestId(otherQuery.getId());
        findingRepository.saveAndFlush(kept);

        // A derived delete needs a transaction; the caller supplies it in production too.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                findingRepository.deleteAllByQueryRequestId(query.getId()));

        assertThat(findingRepository.findAllByQueryRequestIdOrderByStatementIndexAscLineNumberAsc(query.getId()))
                .isEmpty();
        assertThat(findingRepository.findAllByQueryRequestIdOrderByStatementIndexAscLineNumberAsc(otherQuery.getId()))
                .hasSize(1);
    }

    private QuerySqlReviewFindingEntity newFinding(String ruleId, int statementIndex, Integer line, String args) {
        var entity = new QuerySqlReviewFindingEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequestId(query.getId());
        entity.setRuleId(ruleId);
        entity.setSeverity(SqlReviewSeverity.WARN);
        entity.setStatementIndex(statementIndex);
        entity.setLineNumber(line);
        entity.setArgs(args);
        return entity;
    }

    private QueryRequestEntity newQuery() {
        var entity = new QueryRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setDatasource(datasource);
        entity.setSubmittedBy(submitter);
        entity.setSqlText("SELECT * FROM users");
        entity.setQueryType(QueryType.SELECT);
        entity.setStatus(QueryStatus.EXECUTED);
        return entity;
    }

    private OrganizationEntity newOrg() {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("SqlReview-" + UUID.randomUUID());
        org.setSlug("sqlreview-" + UUID.randomUUID());
        return org;
    }

    private UserEntity newUser() {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("submitter-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("Submitter");
        user.setPasswordHash("hash");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(organization);
        return user;
    }

    private DatasourceEntity newDatasource() {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(organization);
        ds.setName("DS-" + UUID.randomUUID());
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost("nope.invalid");
        ds.setPort(65000);
        ds.setDatabaseName("db");
        ds.setUsername("u");
        ds.setPasswordEncrypted(encryptionService.encrypt("p"));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(5);
        ds.setMaxRowsPerQuery(1000);
        ds.setRequireReviewReads(false);
        ds.setRequireReviewWrites(true);
        ds.setAiAnalysisEnabled(false);
        ds.setActive(true);
        return ds;
    }
}
