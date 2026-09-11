package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QuerySubmitterEvidenceLookupService;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-submitter evidence aggregate against a real Postgres (#968): the {@code FILTER}
 * clauses, the enum-literal comparison on {@code submission_reason}, and the org scope through
 * {@code datasources.organization_id} are all things a mocked repository cannot exercise.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class QuerySubmitterEvidenceLookupIntegrationTest {

    @Autowired QuerySubmitterEvidenceLookupService service;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;

    private final List<UUID> createdQueryIds = new ArrayList<>();
    private final List<UUID> createdDatasourceIds = new ArrayList<>();
    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdOrganizationIds = new ArrayList<>();

    private OrganizationEntity orgA;
    private UserEntity heavy;
    private UserEntity light;
    private UserEntity silent;
    private UserEntity elsewhere;

    @BeforeEach
    void setUp() {
        orgA = saveOrg("evidence-a");
        var orgB = saveOrg("evidence-b");
        var dsA = saveDatasource(orgA);
        var dsB = saveDatasource(orgB);
        heavy = saveUser(orgA);
        light = saveUser(orgA);
        silent = saveUser(orgA);
        elsewhere = saveUser(orgB);

        seedQuery(dsA, heavy, QueryStatus.EXECUTED, "2026-07-01T10:00:00Z", SubmissionReason.USER_SUBMITTED);
        seedQuery(dsA, heavy, QueryStatus.REJECTED, "2026-07-02T10:00:00Z", SubmissionReason.USER_SUBMITTED);
        seedQuery(dsA, heavy, QueryStatus.EXECUTED, "2026-07-03T10:00:00Z", SubmissionReason.EMERGENCY_ACCESS);
        seedQuery(dsA, heavy, QueryStatus.FAILED, "2026-07-05T10:00:00Z", SubmissionReason.EMERGENCY_ACCESS);
        seedQuery(dsA, heavy, QueryStatus.APPROVED, "2026-07-09T10:00:00Z", SubmissionReason.RECURRING);
        seedQuery(dsA, light, QueryStatus.EXECUTED, "2026-07-04T10:00:00Z", SubmissionReason.USER_SUBMITTED);
        // Another organization's datasource: must never count, even for a user asked about by id.
        seedQuery(dsB, elsewhere, QueryStatus.EXECUTED, "2026-07-06T10:00:00Z", SubmissionReason.EMERGENCY_ACCESS);
    }

    // Scoped to this class's rows: the Testcontainers database is shared across classes.
    @AfterEach
    void cleanup() {
        queryRequestRepository.deleteAllById(createdQueryIds);
        datasourceRepository.deleteAllById(createdDatasourceIds);
        userRepository.deleteAllById(createdUserIds);
        organizationRepository.deleteAllById(createdOrganizationIds);
    }

    @Test
    void countsEveryStatusAndTheBreakGlassSubsetSeparately() {
        var evidence = service.findBySubmitters(orgA.getId(),
                List.of(heavy.getId(), light.getId(), silent.getId(), elsewhere.getId()));

        assertThat(evidence).containsOnlyKeys(heavy.getId(), light.getId());
        var heavyEvidence = evidence.get(heavy.getId());
        assertThat(heavyEvidence.submittedQueryCount()).isEqualTo(5);
        assertThat(heavyEvidence.lastSubmittedAt()).isEqualTo(Instant.parse("2026-07-09T10:00:00Z"));
        assertThat(heavyEvidence.breakGlassExecutionCount()).isEqualTo(2);
        assertThat(heavyEvidence.lastBreakGlassAt()).isEqualTo(Instant.parse("2026-07-05T10:00:00Z"));
        var lightEvidence = evidence.get(light.getId());
        assertThat(lightEvidence.submittedQueryCount()).isEqualTo(1);
        assertThat(lightEvidence.breakGlassExecutionCount()).isZero();
        assertThat(lightEvidence.lastBreakGlassAt()).isNull();
    }

    @Test
    void aUserFromAnotherOrganizationIsInvisibleEvenWhenAskedForByName() {
        assertThat(service.findBySubmitters(orgA.getId(), List.of(elsewhere.getId()))).isEmpty();
    }

    private void seedQuery(DatasourceEntity ds, UserEntity submitter, QueryStatus status, String when,
                           SubmissionReason reason) {
        var qr = new QueryRequestEntity();
        qr.setId(UUID.randomUUID());
        qr.setDatasource(ds);
        qr.setSubmittedBy(submitter);
        qr.setSqlText("SELECT 1");
        qr.setQueryType(QueryType.SELECT);
        qr.setStatus(status);
        qr.setSubmissionReason(reason);
        qr.setCreatedAt(Instant.parse(when));
        qr.setUpdatedAt(Instant.parse(when));
        createdQueryIds.add(qr.getId());
        queryRequestRepository.save(qr);
    }

    private OrganizationEntity saveOrg(String name) {
        var o = new OrganizationEntity();
        o.setId(UUID.randomUUID());
        o.setName(name);
        o.setSlug(name + "-" + UUID.randomUUID());
        createdOrganizationIds.add(o.getId());
        return organizationRepository.save(o);
    }

    private UserEntity saveUser(OrganizationEntity org) {
        var u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail("u-" + UUID.randomUUID() + "@example.com");
        u.setDisplayName("User");
        u.setPasswordHash("hashed");
        u.setRole(UserRoleType.ANALYST);
        u.setAuthProvider(AuthProviderType.LOCAL);
        u.setActive(true);
        u.setOrganization(org);
        createdUserIds.add(u.getId());
        return userRepository.save(u);
    }

    private DatasourceEntity saveDatasource(OrganizationEntity org) {
        var d = new DatasourceEntity();
        d.setId(UUID.randomUUID());
        d.setOrganization(org);
        d.setName("evidence-ds-" + UUID.randomUUID());
        d.setDbType(DbType.POSTGRESQL);
        d.setHost("h");
        d.setPort(5432);
        d.setDatabaseName("db");
        d.setUsername("u");
        d.setPasswordEncrypted("ENC");
        d.setAiAnalysisEnabled(false);
        d.setActive(true);
        d.setCreatedAt(Instant.now());
        createdDatasourceIds.add(d.getId());
        return datasourceRepository.save(d);
    }
}
