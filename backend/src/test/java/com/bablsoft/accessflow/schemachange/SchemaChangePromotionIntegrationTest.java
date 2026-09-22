package com.bablsoft.accessflow.schemachange;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.proxy.api.DatasourceConnectionPoolManager;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupService;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.internal.scheduled.ScheduledGroupRunJob;
import com.bablsoft.accessflow.schemachange.api.CreateSchemaChangeSetCommand;
import com.bablsoft.accessflow.schemachange.api.PromoteSchemaChangeSetCommand;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionDdlForbiddenException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionLadderBlockedException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionService;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionReviewUnenforceableException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetService;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementInput;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The promotion path end to end (#880) against a real target Postgres: the ladder gate over three
 * rungs, the can_ddl guard, the review guard, and the durable execution trigger — an approved
 * promotion reaches {@code APPLIED} with no human action once {@code ScheduledGroupRunJob} ticks,
 * carrying the post-apply schema snapshot the drift baseline reads.
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@ImportTestcontainers(TestcontainersConfig.class)
class SchemaChangePromotionIntegrationTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> customerDb = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired SchemaChangeSetService changeSetService;
    @Autowired SchemaChangePromotionService promotionService;
    @Autowired RequestGroupService requestGroupService;
    @Autowired ScheduledGroupRunJob scheduledGroupRunJob;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired ReviewPlanRepository reviewPlanRepository;
    @Autowired DeploymentPipelineRepository pipelineRepository;
    @Autowired DeploymentEnvironmentRepository environmentRepository;
    @Autowired DatasourceConnectionPoolManager poolManager;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

    /**
     * ShedLock advice stays live in tests against one shared Redis keyed by job name, and
     * {@code lockAtLeastFor = PT30S} outlives this class — without the override the run job is
     * silently skipped and the promotion never leaves {@code APPROVED}.
     */
    @TestConfiguration
    static class NoOpLockConfig {
        @Bean("lockProvider")
        @Primary
        LockProvider noOpLockProvider() {
            return (LockConfiguration lockConfig) -> Optional.of(new SimpleLock() {
                @Override public void unlock() { }

                @Override public Optional<SimpleLock> extend(Duration lockAtMostFor, Duration lockAtLeastFor) {
                    return Optional.of(this);
                }
            });
        }
    }

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private OrganizationEntity org;
    private UserEntity promoter;
    private ReviewPlanEntity autoPlan;
    private DatasourceEntity datasource;
    private DeploymentPipelineEntity pipeline;
    private DeploymentEnvironmentEntity dev;
    private DeploymentEnvironmentEntity staging;
    private DeploymentEnvironmentEntity prod;

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
        org = saveOrg();
        promoter = saveUser("promoter-" + suffix, UserRoleType.ADMIN);
        autoPlan = savePlan("auto-" + suffix, false);
        datasource = saveDatasource(autoPlan);
        savePermission(promoter, datasource, true);
        pipeline = savePipeline("schema-" + suffix);
        dev = saveEnvironment("dev", 0, datasource.getId(), false);
        staging = saveEnvironment("staging", 1, datasource.getId(), false);
        prod = saveEnvironment("production", 2, datasource.getId(), false);

        try (var connection = DriverManager.getConnection(customerDb.getJdbcUrl(), customerDb.getUsername(),
                customerDb.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS orders_" + tableSuffix());
            statement.execute("DROP TABLE IF EXISTS shipments_" + tableSuffix());
        }
    }

    @AfterEach
    void cleanup() {
        poolManager.evict(datasource.getId());
        jdbcTemplate.update("delete from schema_change_sets where organization_id = ?", org.getId());
        jdbcTemplate.update("delete from request_group_items where group_id in "
                + "(select id from request_groups where organization_id = ?)", org.getId());
        jdbcTemplate.update("delete from request_groups where organization_id = ?", org.getId());
        jdbcTemplate.update("delete from audit_log where organization_id = ?", org.getId());
        environmentRepository.deleteAll(List.of(dev, staging, prod));
        pipelineRepository.deleteById(pipeline.getId());
        permissionRepository.findByUser_IdAndDatasource_Id(promoter.getId(), datasource.getId())
                .ifPresent(permissionRepository::delete);
        datasourceRepository.deleteById(datasource.getId());
        reviewPlanRepository.deleteById(autoPlan.getId());
        userRepository.deleteById(promoter.getId());
        organizationRepository.deleteById(org.getId());
    }

    @Test
    void anApprovedPromotionReachesAppliedWithNoHumanActionAndSnapshotsTheTarget() {
        var changeSet = changeSet("CREATE TABLE orders_" + tableSuffix() + " (id INT PRIMARY KEY)");

        var promotion = promotionService.promote(org.getId(), promoter.getId(), changeSet,
                new PromoteSchemaChangeSetCommand(dev.getId(), "10.0.0.7", "junit"));

        assertThat(promotion.status()).isEqualTo(SchemaChangePromotionStatus.PENDING);
        assertThat(promotion.requestGroupId()).isNotNull();
        assertThat(changeSetService.get(org.getId(), changeSet).status()).isEqualTo(SchemaChangeSetStatus.ACTIVE);

        awaitGroup(promotion.requestGroupId(), RequestGroupStatus.APPROVED);
        scheduledGroupRunJob.run();

        awaitPromotion(promotion.id(), SchemaChangePromotionStatus.APPLIED);
        var applied = promotionService.get(org.getId(), promotion.id());
        assertThat(applied.appliedAt()).isNotNull();
        assertThat(applied.snapshotTakenAt()).isNotNull();
        assertThat(applied.schemaSnapshot()).contains("orders_" + tableSuffix());
        assertThat(applied.errorMessage()).isNull();
        assertThat(applied.environmentName()).isEqualTo("dev");
        assertThat(auditActions()).contains(AuditAction.SCHEMA_CHANGE_PROMOTION_SUBMITTED.name(),
                AuditAction.SCHEMA_CHANGE_PROMOTION_APPLIED.name());
    }

    /** {@code IF NOT EXISTS} because all three rungs point at one database here — the gate is what is under test. */
    @Test
    void theLadderIsEnforcedRungByRung() {
        var changeSet = changeSet(
                "CREATE TABLE IF NOT EXISTS shipments_" + tableSuffix() + " (id INT PRIMARY KEY)");

        assertThatThrownBy(() -> promotionService.promote(org.getId(), promoter.getId(), changeSet,
                new PromoteSchemaChangeSetCommand(prod.getId())))
                .isInstanceOf(SchemaChangePromotionLadderBlockedException.class);

        apply(changeSet, dev);

        assertThatThrownBy(() -> promotionService.promote(org.getId(), promoter.getId(), changeSet,
                new PromoteSchemaChangeSetCommand(prod.getId())))
                .isInstanceOf(SchemaChangePromotionLadderBlockedException.class);

        apply(changeSet, staging);

        var promotion = promotionService.promote(org.getId(), promoter.getId(), changeSet,
                new PromoteSchemaChangeSetCommand(prod.getId()));
        assertThat(promotion.environmentId()).isEqualTo(prod.getId());
        awaitGroup(promotion.requestGroupId(), RequestGroupStatus.APPROVED);
        scheduledGroupRunJob.run();
        awaitPromotion(promotion.id(), SchemaChangePromotionStatus.APPLIED);
    }

    @Test
    void aFailingStatementLeavesThePromotionFailedWithNoSnapshot() {
        var changeSet = changeSet("ALTER TABLE missing_" + tableSuffix() + " ADD COLUMN c INT");

        var promotion = promotionService.promote(org.getId(), promoter.getId(), changeSet,
                new PromoteSchemaChangeSetCommand(dev.getId()));
        awaitGroup(promotion.requestGroupId(), RequestGroupStatus.APPROVED);
        scheduledGroupRunJob.run();

        awaitPromotion(promotion.id(), SchemaChangePromotionStatus.FAILED);
        var failed = promotionService.get(org.getId(), promotion.id());
        assertThat(failed.schemaSnapshot()).isNull();
        assertThat(failed.snapshotTakenAt()).isNull();
        assertThat(failed.appliedAt()).isNull();
        assertThat(failed.errorMessage()).isNotBlank();
        assertThat(auditActions()).contains(AuditAction.SCHEMA_CHANGE_PROMOTION_FAILED.name());
    }

    @Test
    void aPromoterWithoutDdlIsRefusedEvenAsAnOrgAdmin() {
        var changeSet = changeSet("CREATE TABLE orders_" + tableSuffix() + " (id INT PRIMARY KEY)");
        permissionRepository.findByUser_IdAndDatasource_Id(promoter.getId(), datasource.getId())
                .ifPresent(permissionRepository::delete);

        assertThatThrownBy(() -> promotionService.promote(org.getId(), promoter.getId(), changeSet,
                new PromoteSchemaChangeSetCommand(dev.getId())))
                .isInstanceOf(SchemaChangePromotionDdlForbiddenException.class);
        assertThat(changeSetService.get(org.getId(), changeSet).status()).isEqualTo(SchemaChangeSetStatus.DRAFT);
    }

    @Test
    void anEnvironmentRequiringReviewRefusesATargetThatWouldAutoApprove() {
        var changeSet = changeSet("CREATE TABLE orders_" + tableSuffix() + " (id INT PRIMARY KEY)");
        dev.setRequireReview(true);
        environmentRepository.saveAndFlush(dev);

        assertThatThrownBy(() -> promotionService.promote(org.getId(), promoter.getId(), changeSet,
                new PromoteSchemaChangeSetCommand(dev.getId())))
                .isInstanceOf(SchemaChangePromotionReviewUnenforceableException.class);
    }

    private void apply(UUID changeSetId, DeploymentEnvironmentEntity environment) {
        var promotion = promotionService.promote(org.getId(), promoter.getId(), changeSetId,
                new PromoteSchemaChangeSetCommand(environment.getId()));
        awaitGroup(promotion.requestGroupId(), RequestGroupStatus.APPROVED);
        scheduledGroupRunJob.run();
        awaitPromotion(promotion.id(), SchemaChangePromotionStatus.APPLIED);
    }

    private void awaitGroup(UUID groupId, RequestGroupStatus expected) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                requestGroupService.get(groupId, org.getId(), promoter.getId(), true).status()).isEqualTo(expected));
    }

    private void awaitPromotion(UUID promotionId, SchemaChangePromotionStatus expected) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(
                promotionService.get(org.getId(), promotionId).status()).isEqualTo(expected));
    }

    private List<String> auditActions() {
        return jdbcTemplate.queryForList("select action from audit_log where organization_id = ?", String.class,
                org.getId());
    }

    private UUID changeSet(String sql) {
        return changeSetService.create(org.getId(), promoter.getId(), new CreateSchemaChangeSetCommand(
                pipeline.getId(), "set-" + UUID.randomUUID().toString().substring(0, 8), null,
                List.of(new SchemaChangeSetStatementInput(sql)))).id();
    }

    private String tableSuffix() {
        return suffix.replace('-', '_');
    }

    private OrganizationEntity saveOrg() {
        var entity = new OrganizationEntity();
        entity.setId(UUID.randomUUID());
        entity.setName("Promotion " + suffix);
        entity.setSlug("promotion-" + suffix);
        return organizationRepository.save(entity);
    }

    private UserEntity saveUser(String prefix, UserRoleType role) {
        var entity = new UserEntity();
        entity.setId(UUID.randomUUID());
        entity.setEmail(prefix + "@example.com");
        entity.setDisplayName(prefix);
        entity.setPasswordHash("x");
        entity.setRole(role);
        entity.setAuthProvider(AuthProviderType.LOCAL);
        entity.setActive(true);
        entity.setOrganization(org);
        return userRepository.save(entity);
    }

    private ReviewPlanEntity savePlan(String name, boolean requiresHumanApproval) {
        var entity = new ReviewPlanEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(org);
        entity.setName(name);
        entity.setRequiresAiReview(false);
        entity.setRequiresHumanApproval(requiresHumanApproval);
        entity.setMinApprovalsRequired(1);
        return reviewPlanRepository.save(entity);
    }

    private DatasourceEntity saveDatasource(ReviewPlanEntity plan) {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
        ds.setName("target-" + suffix);
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost(customerDb.getHost());
        ds.setPort(customerDb.getMappedPort(5432));
        ds.setDatabaseName(customerDb.getDatabaseName());
        ds.setUsername(customerDb.getUsername());
        ds.setPasswordEncrypted(encryptionService.encrypt(customerDb.getPassword()));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(3);
        ds.setMaxRowsPerQuery(1000);
        ds.setRequireReviewReads(false);
        ds.setRequireReviewWrites(false);
        ds.setAiAnalysisEnabled(false);
        ds.setActive(true);
        ds.setEnvironment(DatasourceEnvironment.DEVELOPMENT);
        ds.setReviewPlan(plan);
        return datasourceRepository.save(ds);
    }

    private void savePermission(UserEntity user, DatasourceEntity ds, boolean canDdl) {
        var entity = new DatasourceUserPermissionEntity();
        entity.setId(UUID.randomUUID());
        entity.setDatasource(ds);
        entity.setUser(user);
        entity.setCreatedBy(user);
        entity.setCanRead(true);
        entity.setCanWrite(true);
        entity.setCanDdl(canDdl);
        permissionRepository.save(entity);
    }

    private DeploymentPipelineEntity savePipeline(String name) {
        var entity = new DeploymentPipelineEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(org.getId());
        entity.setName(name);
        entity.setProvider(PipelineProvider.GITHUB_ACTIONS);
        entity.setActive(true);
        entity.setAiAnalysisEnabled(false);
        return pipelineRepository.save(entity);
    }

    private DeploymentEnvironmentEntity saveEnvironment(String name, int sortOrder, UUID datasourceId,
                                                        boolean requireReview) {
        var entity = new DeploymentEnvironmentEntity();
        entity.setId(UUID.randomUUID());
        entity.setPipelineId(pipeline.getId());
        entity.setName(name);
        entity.setSortOrder(sortOrder);
        entity.setRequireReview(requireReview);
        entity.setAllowBreakGlass(false);
        entity.setDatasourceId(datasourceId);
        return environmentRepository.save(entity);
    }
}
