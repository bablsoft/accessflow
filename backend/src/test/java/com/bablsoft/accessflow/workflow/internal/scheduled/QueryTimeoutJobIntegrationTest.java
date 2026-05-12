package com.bablsoft.accessflow.workflow.internal.scheduled;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.events.QueryTimedOutEvent;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewDecisionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@ImportTestcontainers(TestcontainersConfig.class)
class QueryTimeoutJobIntegrationTest {

    @Autowired QueryTimeoutJob job;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired ReviewPlanRepository reviewPlanRepository;
    @Autowired ReviewDecisionRepository reviewDecisionRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CapturedTimedOutEvents capturedEvents;

    private UUID queryRequestId;
    private UUID datasourceId;

    @TestConfiguration
    static class CaptureConfig {
        @Bean CapturedTimedOutEvents capturedEvents() {
            return new CapturedTimedOutEvents();
        }

        /**
         * Replaces the Redis-backed lock provider so tests can call {@code job.run()} directly
         * without contending with the auto-fired scheduled invocation (which holds the lock for
         * its {@code lockAtLeastFor} window). The no-op lock always succeeds and never blocks.
         * Bean name matches the production bean so it overrides via
         * {@code spring.main.allow-bean-definition-overriding=true}.
         */
        @Bean("lockProvider")
        @Primary
        LockProvider noOpLockProvider() {
            return (LockConfiguration lockConfig) -> Optional.of(new SimpleLock() {
                @Override public void unlock() {}
                @Override public Optional<SimpleLock> extend(java.time.Duration lockAtMostFor,
                                                             java.time.Duration lockAtLeastFor) {
                    return Optional.of(this);
                }
            });
        }
    }

    static class CapturedTimedOutEvents {
        final List<QueryTimedOutEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void onTimedOut(QueryTimedOutEvent event) {
            events.add(event);
        }
    }

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        var privateKey = (RSAPrivateCrtKey) kp.getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        // Suppress automatic scheduler firing during the test — we drive job.run() explicitly.
        registry.add("accessflow.workflow.timeout-poll-interval", () -> "PT24H");
    }

    @BeforeEach
    void setUp() {
        cleanup();
        capturedEvents.events.clear();

        var organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("Primary");
        organization.setSlug("primary-" + UUID.randomUUID());
        organizationRepository.save(organization);

        var submitter = new UserEntity();
        submitter.setId(UUID.randomUUID());
        submitter.setEmail("submitter-" + UUID.randomUUID() + "@example.com");
        submitter.setDisplayName("Submitter");
        submitter.setPasswordHash("hash");
        submitter.setRole(UserRoleType.ANALYST);
        submitter.setAuthProvider(AuthProviderType.LOCAL);
        submitter.setActive(true);
        submitter.setOrganization(organization);
        userRepository.save(submitter);

        var plan = new ReviewPlanEntity();
        plan.setId(UUID.randomUUID());
        plan.setOrganization(organization);
        plan.setName("ZeroTimeoutPlan-" + UUID.randomUUID());
        plan.setRequiresAiReview(false);
        plan.setRequiresHumanApproval(true);
        plan.setMinApprovalsRequired(1);
        plan.setApprovalTimeoutHours(0);
        plan.setAutoApproveReads(false);
        reviewPlanRepository.save(plan);

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
        ds.setAiAnalysisEnabled(true);
        ds.setActive(true);
        ds.setReviewPlan(plan);
        datasourceRepository.save(ds);
        datasourceId = ds.getId();

        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(ds);
        query.setSubmittedBy(submitter);
        query.setSqlText("SELECT 1");
        query.setQueryType(QueryType.SELECT);
        query.setStatus(QueryStatus.PENDING_REVIEW);
        // Backdate created_at so the interval arithmetic in the native query treats this as overdue
        // even when approval_timeout_hours is 0 — guards against clock skew on the test container.
        query.setCreatedAt(Instant.now().minusSeconds(60));
        queryRequestRepository.save(query);
        queryRequestId = query.getId();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("UPDATE query_requests SET ai_analysis_id = NULL");
        reviewDecisionRepository.deleteAll();
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
        reviewPlanRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void runAutoRejectsTimedOutQueriesAndPublishesEvent() {
        job.run();

        var query = queryRequestRepository.findById(queryRequestId).orElseThrow();
        assertThat(query.getStatus()).isEqualTo(QueryStatus.TIMED_OUT);

        // No review_decisions row should be inserted for a timeout — that's the design.
        assertThat(reviewDecisionRepository
                .findAllByQueryRequest_IdOrderByDecidedAtAsc(queryRequestId))
                .isEmpty();

        assertThat(capturedEvents.events).hasSize(1);
        var event = capturedEvents.events.get(0);
        assertThat(event.queryRequestId()).isEqualTo(queryRequestId);
        assertThat(event.approvalTimeoutHours()).isZero();
    }

    @Test
    void runIsIdempotent() {
        job.run();
        job.run();

        var query = queryRequestRepository.findById(queryRequestId).orElseThrow();
        assertThat(query.getStatus()).isEqualTo(QueryStatus.TIMED_OUT);
        // Only the first invocation transitions; the second tick sees TIMED_OUT and does nothing.
        assertThat(capturedEvents.events).hasSize(1);
    }

    @Test
    void runIgnoresQueriesNotPastTimeout() {
        // Bump the timeout out of reach so the query is no longer overdue.
        // Update via JdbcTemplate to avoid lazy-loading the proxy outside a session.
        jdbcTemplate.update("UPDATE review_plans SET approval_timeout_hours = 48");
        jdbcTemplate.update("UPDATE query_requests SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now()), queryRequestId);

        job.run();

        var loaded = queryRequestRepository.findById(queryRequestId).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(capturedEvents.events).isEmpty();
    }
}
