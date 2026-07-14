package com.bablsoft.accessflow.access;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.access.api.AccessGrantExpiryService;
import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.api.AccessRequestService;
import com.bablsoft.accessflow.access.api.AccessRequestService.SubmitCommand;
import com.bablsoft.accessflow.access.api.AccessReviewService;
import com.bablsoft.accessflow.access.api.AccessReviewService.ReviewerContext;
import com.bablsoft.accessflow.access.api.AccessResourceKind;
import com.bablsoft.accessflow.access.internal.persistence.repo.AccessGrantRequestRepository;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUserPermissionRepository;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanApproverEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanApproverRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AccessGrantLifecycleIntegrationTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired ReviewPlanRepository reviewPlanRepository;
    @Autowired ReviewPlanApproverRepository reviewPlanApproverRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired ApiConnectorRepository connectorRepository;
    @Autowired ApiConnectorUserPermissionRepository connectorPermissionRepository;
    @Autowired AccessGrantRequestRepository requestRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired AccessRequestService accessRequestService;
    @Autowired AccessReviewService accessReviewService;
    @Autowired AccessGrantExpiryService accessGrantExpiryService;
    @Autowired JdbcTemplate jdbcTemplate;

    private OrganizationEntity organization;
    private UserEntity requester;
    private UserEntity reviewer;
    private DatasourceEntity datasource;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var privateKey = (RSAPrivateCrtKey) kpg.generateKeyPair().getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @BeforeEach
    void setUp() {
        cleanup();
        organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("Primary");
        organization.setSlug("primary-" + UUID.randomUUID());
        organizationRepository.save(organization);

        requester = user("requester", UserRoleType.ANALYST);
        reviewer = user("reviewer", UserRoleType.REVIEWER);

        var plan = new ReviewPlanEntity();
        plan.setId(UUID.randomUUID());
        plan.setOrganization(organization);
        plan.setName("plan-" + UUID.randomUUID());
        plan.setRequiresAiReview(false);
        plan.setRequiresHumanApproval(true);
        plan.setMinApprovalsRequired(1);
        plan.setApprovalTimeoutHours(24);
        plan.setAutoApproveReads(false);
        reviewPlanRepository.save(plan);

        var approver = new ReviewPlanApproverEntity();
        approver.setId(UUID.randomUUID());
        approver.setReviewPlan(plan);
        approver.setRole(UserRoleType.REVIEWER);
        approver.setStage(1);
        reviewPlanApproverRepository.save(approver);

        datasource = new DatasourceEntity();
        datasource.setId(UUID.randomUUID());
        datasource.setOrganization(organization);
        datasource.setName("DS-" + UUID.randomUUID());
        datasource.setDbType(DbType.POSTGRESQL);
        datasource.setHost("nope.invalid");
        datasource.setPort(65000);
        datasource.setDatabaseName("db");
        datasource.setUsername("u");
        datasource.setPasswordEncrypted(encryptionService.encrypt("p"));
        datasource.setSslMode(SslMode.DISABLE);
        datasource.setConnectionPoolSize(5);
        datasource.setMaxRowsPerQuery(1000);
        datasource.setRequireReviewReads(false);
        datasource.setRequireReviewWrites(true);
        datasource.setReviewPlan(plan);
        datasource.setAiAnalysisEnabled(false);
        datasource.setActive(true);
        datasourceRepository.save(datasource);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM access_grant_decision");
        jdbcTemplate.update("DELETE FROM access_grant_request");
        jdbcTemplate.update("DELETE FROM audit_log");
        permissionRepository.deleteAll();
        connectorPermissionRepository.deleteAll();
        connectorRepository.deleteAll();
        datasourceRepository.deleteAll();
        reviewPlanApproverRepository.deleteAll();
        reviewPlanRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    private UserEntity user(String name, UserRoleType role) {
        var u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail(name + "-" + UUID.randomUUID() + "@example.com");
        u.setDisplayName(name);
        u.setPasswordHash("hash");
        u.setRole(role);
        u.setAuthProvider(AuthProviderType.LOCAL);
        u.setActive(true);
        u.setOrganization(organization);
        return userRepository.save(u);
    }

    @Test
    void submitApproveMaterialisesTimeBoxedGrantThenExpiryRevokesIt() {
        // Submit
        var view = accessRequestService.submit(new SubmitCommand(organization.getId(),
                requester.getId(), datasource.getId(), null, true, true, false,
                List.of("public", "analytics"), null, null, "PT4H", "deploy hotfix", false));
        assertThat(view.status()).isEqualTo(AccessGrantStatus.PENDING);

        // Approve (single-stage REVIEWER plan → final approval)
        var outcome = accessReviewService.approve(view.id(),
                new ReviewerContext(reviewer.getId(), organization.getId(), UserRoleType.REVIEWER),
                "ok");
        assertThat(outcome.resultingStatus()).isEqualTo(AccessGrantStatus.APPROVED);

        // A time-boxed permission row exists with expires_at set + array scope round-tripped
        var permission = permissionRepository
                .findByUser_IdAndDatasource_Id(requester.getId(), datasource.getId())
                .orElseThrow();
        assertThat(permission.getExpiresAt()).isNotNull();
        assertThat(permission.isCanRead()).isTrue();
        assertThat(permission.isCanWrite()).isTrue();
        assertThat(permission.getAllowedSchemas()).containsExactlyInAnyOrder("public", "analytics");

        var stored = requestRepository.findById(view.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AccessGrantStatus.APPROVED);
        assertThat(stored.getGrantedPermissionId()).isEqualTo(permission.getId());

        // Force expiry: backdate expires_at, then run the expiry service
        jdbcTemplate.update("UPDATE access_grant_request SET expires_at = now() - interval '1 hour' "
                + "WHERE id = ?", view.id());
        var expired = accessGrantExpiryService.findExpiredGrantedIds(Instant.now());
        assertThat(expired).contains(view.id());
        assertThat(accessGrantExpiryService.expireAndRevoke(view.id())).isTrue();

        // Permission revoked + request EXPIRED + audit row written
        assertThat(permissionRepository
                .findByUser_IdAndDatasource_Id(requester.getId(), datasource.getId())).isEmpty();
        assertThat(requestRepository.findById(view.id()).orElseThrow().getStatus())
                .isEqualTo(AccessGrantStatus.EXPIRED);
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'ACCESS_GRANT_EXPIRED'", Integer.class);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void connectorSubmitApproveMaterialisesTimeBoxedGrantThenExpiryRevokesIt() {
        var admin = user("admin", UserRoleType.ADMIN);
        var connector = saveConnector();

        // Submit a connector-targeted request (no operation allow-list = all operations)
        var view = accessRequestService.submit(new SubmitCommand(organization.getId(),
                requester.getId(), null, connector.getId(), true, true, false, null, null, null,
                "PT4H", "call billing API", false));
        assertThat(view.status()).isEqualTo(AccessGrantStatus.PENDING);
        assertThat(view.resourceKind()).isEqualTo(AccessResourceKind.API_CONNECTOR);
        assertThat(view.connectorId()).isEqualTo(connector.getId());
        assertThat(view.datasourceId()).isNull();

        // Approve as admin (backstop approver — the connector has no review plan attached)
        var outcome = accessReviewService.approve(view.id(),
                new ReviewerContext(admin.getId(), organization.getId(), UserRoleType.ADMIN), "ok");
        assertThat(outcome.resultingStatus()).isEqualTo(AccessGrantStatus.APPROVED);

        // A time-boxed api_connector_user_permissions row exists with expires_at set
        var permission = connectorPermissionRepository
                .findByConnectorIdAndUserId(connector.getId(), requester.getId())
                .orElseThrow();
        assertThat(permission.getExpiresAt()).isNotNull();
        assertThat(permission.isCanRead()).isTrue();
        assertThat(permission.isCanWrite()).isTrue();
        assertThat(permission.isCanBreakGlass()).isFalse();

        var stored = requestRepository.findById(view.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(AccessGrantStatus.APPROVED);
        assertThat(stored.getGrantedPermissionId()).isEqualTo(permission.getId());

        // Force expiry: backdate expires_at, then run the expiry service
        jdbcTemplate.update("UPDATE access_grant_request SET expires_at = now() - interval '1 hour' "
                + "WHERE id = ?", view.id());
        assertThat(accessGrantExpiryService.findExpiredGrantedIds(Instant.now())).contains(view.id());
        assertThat(accessGrantExpiryService.expireAndRevoke(view.id())).isTrue();

        // Permission revoked + request EXPIRED + audit row written with connector metadata
        assertThat(connectorPermissionRepository
                .findByConnectorIdAndUserId(connector.getId(), requester.getId())).isEmpty();
        assertThat(requestRepository.findById(view.id()).orElseThrow().getStatus())
                .isEqualTo(AccessGrantStatus.EXPIRED);
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'ACCESS_GRANT_EXPIRED' "
                        + "AND metadata ->> 'resource_kind' = 'API_CONNECTOR' "
                        + "AND metadata ->> 'connector_id' = ?", Integer.class,
                connector.getId().toString());
        assertThat(auditCount).isEqualTo(1);
    }

    private ApiConnectorEntity saveConnector() {
        var connector = new ApiConnectorEntity();
        connector.setId(UUID.randomUUID());
        connector.setOrganizationId(organization.getId());
        connector.setName("billing-" + UUID.randomUUID());
        connector.setProtocol(ApiProtocol.REST);
        connector.setBaseUrl("https://api.test");
        connector.setActive(true);
        return connectorRepository.save(connector);
    }

    @Test
    void cancellingPendingRequestTransitionsToCancelled() {
        var view = accessRequestService.submit(new SubmitCommand(organization.getId(),
                requester.getId(), datasource.getId(), null, true, false, false, null, null, null,
                "PT2H", "j", false));

        accessRequestService.cancel(view.id(), requester.getId(), organization.getId());

        assertThat(requestRepository.findById(view.id()).orElseThrow().getStatus())
                .isEqualTo(AccessGrantStatus.CANCELLED);
    }

    @Test
    void listMineReturnsRequestersOwnRequests() {
        accessRequestService.submit(new SubmitCommand(organization.getId(), requester.getId(),
                datasource.getId(), null, true, false, false, null, null, null, "PT2H", "j", false));

        var page = accessRequestService.listMine(organization.getId(), requester.getId(), null,
                com.bablsoft.accessflow.core.api.PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).requesterId()).isEqualTo(requester.getId());
    }
}
