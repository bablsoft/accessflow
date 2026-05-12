package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.DecisionType;
import com.bablsoft.accessflow.core.api.QueryRequestStateService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RecordApprovalCommand;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ReviewDecisionRepository;
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
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class DefaultQueryRequestStateServicePostgresIntegrationTest {

    @Autowired QueryRequestStateService stateService;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired ReviewDecisionRepository reviewDecisionRepository;
    @Autowired AiAnalysisRepository aiAnalysisRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID queryRequestId;
    private UUID reviewerId;

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
    }

    @BeforeEach
    void setUp() {
        cleanup();
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

        var reviewer = new UserEntity();
        reviewer.setId(UUID.randomUUID());
        reviewer.setEmail("reviewer-" + UUID.randomUUID() + "@example.com");
        reviewer.setDisplayName("Reviewer");
        reviewer.setPasswordHash("hash");
        reviewer.setRole(UserRoleType.REVIEWER);
        reviewer.setAuthProvider(AuthProviderType.LOCAL);
        reviewer.setActive(true);
        reviewer.setOrganization(organization);
        userRepository.save(reviewer);
        reviewerId = reviewer.getId();

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
        datasourceRepository.save(ds);

        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(ds);
        query.setSubmittedBy(submitter);
        query.setSqlText("SELECT 1");
        query.setQueryType(QueryType.SELECT);
        query.setStatus(QueryStatus.PENDING_REVIEW);
        queryRequestRepository.save(query);
        queryRequestId = query.getId();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("UPDATE query_requests SET ai_analysis_id = NULL");
        reviewDecisionRepository.deleteAll();
        aiAnalysisRepository.deleteAll();
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void replayingApprovalReturnsExistingDecisionAndDoesNotInsertDuplicate() {
        var first = stateService.recordApprovalAndAdvance(new RecordApprovalCommand(
                queryRequestId, reviewerId, 1, 1, true, "ok"));
        assertThat(first.wasIdempotentReplay()).isFalse();
        assertThat(first.resultingStatus()).isEqualTo(QueryStatus.APPROVED);

        // Reset query back to PENDING_REVIEW so the second call goes through the same path
        // (the unique index would otherwise prevent any second insert anyway).
        var query = queryRequestRepository.findById(queryRequestId).orElseThrow();
        query.setStatus(QueryStatus.PENDING_REVIEW);
        queryRequestRepository.save(query);

        var second = stateService.recordApprovalAndAdvance(new RecordApprovalCommand(
                queryRequestId, reviewerId, 1, 1, true, "still ok"));

        assertThat(second.wasIdempotentReplay()).isTrue();
        assertThat(second.decisionId()).isEqualTo(first.decisionId());
        assertThat(reviewDecisionRepository
                .findAllByQueryRequest_IdOrderByDecidedAtAsc(queryRequestId))
                .hasSize(1);
    }

    @Test
    void rawDuplicateInsertViolatesUniqueIndex() {
        var first = stateService.recordApprovalAndAdvance(new RecordApprovalCommand(
                queryRequestId, reviewerId, 1, 1, true, "ok"));
        assertThat(first.wasIdempotentReplay()).isFalse();

        // Using JdbcTemplate to bypass the service's existence-check confirms the V11 unique
        // index is in place at the DB level — a defense-in-depth backstop against bugs in the
        // service layer.
        Throwable thrown = null;
        try {
            jdbcTemplate.update(
                    "INSERT INTO review_decisions (id, query_request_id, reviewer_id, decision, stage)"
                            + " VALUES (?, ?, ?, 'APPROVED'::decision, ?)",
                    UUID.randomUUID(), queryRequestId, reviewerId, 1);
        } catch (Throwable t) {
            thrown = t;
        }
        assertThat(thrown).isNotNull();
    }

    @Test
    void recordRejectionPersistsAndTransitionsToRejected() {
        var result = stateService.recordRejection(queryRequestId, reviewerId, 1, "no");

        assertThat(result.resultingStatus()).isEqualTo(QueryStatus.REJECTED);
        var query = queryRequestRepository.findById(queryRequestId).orElseThrow();
        assertThat(query.getStatus()).isEqualTo(QueryStatus.REJECTED);
        var decisions = reviewDecisionRepository
                .findAllByQueryRequest_IdOrderByDecidedAtAsc(queryRequestId);
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecision()).isEqualTo(DecisionType.REJECTED);
    }

    @Test
    void recordChangesRequestedKeepsPendingReviewAndPersistsDecision() {
        var result = stateService.recordChangesRequested(queryRequestId, reviewerId, 1,
                "fix WHERE");

        assertThat(result.resultingStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        var query = queryRequestRepository.findById(queryRequestId).orElseThrow();
        assertThat(query.getStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
        var decisions = reviewDecisionRepository
                .findAllByQueryRequest_IdOrderByDecidedAtAsc(queryRequestId);
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).getDecision()).isEqualTo(DecisionType.REQUESTED_CHANGES);
    }

    @Test
    void transitionToWritesNextStatus() {
        // bring it back to PENDING_AI to test transition
        var query = queryRequestRepository.findById(queryRequestId).orElseThrow();
        query.setStatus(QueryStatus.PENDING_AI);
        queryRequestRepository.save(query);

        stateService.transitionTo(queryRequestId, QueryStatus.PENDING_AI,
                QueryStatus.PENDING_REVIEW);

        var loaded = queryRequestRepository.findById(queryRequestId).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(QueryStatus.PENDING_REVIEW);
    }
}
