package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.events.AiAnalysisCompletedEvent;
import com.bablsoft.accessflow.core.events.AiAnalysisFailedEvent;
import com.bablsoft.accessflow.core.events.QueryReadyForReviewEvent;
import com.bablsoft.accessflow.core.internal.persistence.entity.AiAnalysisEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.api.ApiKeyAuthentication;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.serviceaccounts.internal.web.ApiKeyRequestFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import com.bablsoft.accessflow.core.api.PageRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AuditLogIntegrationTest {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AuditLogService auditLogService;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired AiAnalysisRepository aiAnalysisRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID organizationId;
    private UUID submitterId;
    private UUID queryRequestId;

    @BeforeEach
    void setUp() {
        cleanup();
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Org");
        org.setSlug("org-" + UUID.randomUUID());
        organizationRepository.save(org);
        organizationId = org.getId();

        var submitter = new UserEntity();
        submitter.setId(UUID.randomUUID());
        submitter.setEmail("alice@example.com");
        submitter.setDisplayName("Alice");
        submitter.setPasswordHash("h");
        submitter.setRole(UserRoleType.ANALYST);
        submitter.setAuthProvider(AuthProviderType.LOCAL);
        submitter.setActive(true);
        submitter.setOrganization(org);
        userRepository.save(submitter);
        submitterId = submitter.getId();

        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
        ds.setName("DS");
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost("nope.invalid");
        ds.setPort(65000);
        ds.setDatabaseName("db");
        ds.setUsername("u");
        ds.setPasswordEncrypted(encryptionService.encrypt("p"));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(2);
        ds.setMaxRowsPerQuery(1000);
        ds.setRequireReviewReads(false);
        ds.setRequireReviewWrites(false);
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
        jdbcTemplate.update("DELETE FROM audit_log");
        jdbcTemplate.update("UPDATE query_requests SET ai_analysis_id = NULL");
        aiAnalysisRepository.deleteAll();
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void recordPersistsToAuditLog() {
        auditLogService.record(new AuditEntry(
                AuditAction.USER_LOGIN,
                AuditResourceType.USER,
                submitterId,
                organizationId,
                submitterId,
                Map.of("email", "alice@example.com"),
                "127.0.0.1",
                "ua/1"));

        var rows = jdbcTemplate.queryForList(
                "SELECT action, host(ip_address) AS ip_address, user_agent FROM audit_log WHERE actor_id = ?",
                submitterId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("action")).isEqualTo("USER_LOGIN");
        assertThat(rows.get(0).get("ip_address")).isEqualTo("127.0.0.1");
        assertThat(rows.get(0).get("user_agent")).isEqualTo("ua/1");
    }

    @Test
    void aiCompletedListenerWritesQueryAiAnalyzedRow() {
        // The AI module persists the AiAnalysisEntity; we need it to exist for the event.
        var ai = new AiAnalysisEntity();
        ai.setId(UUID.randomUUID());
        ai.setQueryRequest(queryRequestRepository.findById(queryRequestId).orElseThrow());
        ai.setAiProvider(AiProviderType.ANTHROPIC);
        ai.setAiModel("claude-test");
        ai.setRiskLevel(RiskLevel.HIGH);
        ai.setRiskScore(80);
        ai.setSummary("test");
        aiAnalysisRepository.save(ai);

        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                eventPublisher.publishEvent(new AiAnalysisCompletedEvent(
                        queryRequestId, ai.getId(), RiskLevel.HIGH)));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var rows = auditLogService.query(organizationId,
                    new AuditLogQuery(null, AuditAction.QUERY_AI_ANALYZED, null, queryRequestId,
                            null, null),
                    PageRequest.of(0, 20));
            assertThat(rows.totalElements()).isEqualTo(1);
            var view = rows.content().get(0);
            assertThat(view.actorId()).isNull();
            assertThat(view.metadata()).containsEntry("risk_level", "HIGH");
        });
    }

    @Test
    void aiFailedListenerWritesQueryAiFailedRow() {
        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                eventPublisher.publishEvent(new AiAnalysisFailedEvent(queryRequestId, "boom")));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var rows = auditLogService.query(organizationId,
                    new AuditLogQuery(null, AuditAction.QUERY_AI_FAILED, null, queryRequestId,
                            null, null),
                    PageRequest.of(0, 20));
            assertThat(rows.totalElements()).isEqualTo(1);
            assertThat(rows.content().get(0).metadata()).containsEntry("reason", "boom");
        });
    }

    @Test
    void readyForReviewListenerWritesAuditRow() {
        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                eventPublisher.publishEvent(new QueryReadyForReviewEvent(queryRequestId)));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var rows = auditLogService.query(organizationId,
                    new AuditLogQuery(null, AuditAction.QUERY_REVIEW_REQUESTED, null,
                            queryRequestId, null, null),
                    PageRequest.of(0, 20));
            assertThat(rows.totalElements()).isEqualTo(1);
        });
    }

    @Test
    void queryScopesByOrganizationId() {
        auditLogService.record(new AuditEntry(
                AuditAction.QUERY_SUBMITTED,
                AuditResourceType.QUERY_REQUEST,
                queryRequestId,
                organizationId,
                submitterId,
                Map.of(),
                null,
                null));

        var otherOrg = UUID.randomUUID();
        var rows = auditLogService.query(otherOrg, AuditLogQuery.empty(), PageRequest.of(0, 20));
        assertThat(rows.totalElements()).isZero();

        var ours = auditLogService.query(organizationId, AuditLogQuery.empty(), PageRequest.of(0, 20));
        assertThat(ours.totalElements()).isEqualTo(1);
    }

    /** #875: the on-behalf-of filter runs against the JSONB metadata on a real Postgres. */
    @Test
    void queryFiltersByOnBehalfOfUserIdInsideTheMetadata() {
        var principal = UUID.randomUUID();
        auditLogService.record(new AuditEntry(AuditAction.QUERY_SUBMITTED, AuditResourceType.QUERY_REQUEST,
                queryRequestId, organizationId, submitterId,
                Map.of("on_behalf_of_user_id", principal.toString(), "service_account", true), null, null));
        auditLogService.record(new AuditEntry(AuditAction.QUERY_SUBMITTED, AuditResourceType.QUERY_REQUEST,
                UUID.randomUUID(), organizationId, submitterId,
                Map.of("on_behalf_of_user_id", UUID.randomUUID().toString()), null, null));
        auditLogService.record(new AuditEntry(AuditAction.QUERY_SUBMITTED, AuditResourceType.QUERY_REQUEST,
                UUID.randomUUID(), organizationId, submitterId, Map.of(), null, null));

        var filtered = auditLogService.query(organizationId,
                new AuditLogQuery(null, null, null, null, null, null, principal), PageRequest.of(0, 20));

        assertThat(filtered.totalElements()).isEqualTo(1);
        assertThat(filtered.content().get(0).resourceId()).isEqualTo(queryRequestId);
        assertThat(filtered.content().get(0).metadata()).containsEntry("on_behalf_of_user_id", principal.toString());

        var unfiltered = auditLogService.query(organizationId, AuditLogQuery.empty(), PageRequest.of(0, 20));
        assertThat(unfiltered.totalElements()).isEqualTo(3);
    }

    /**
     * #874: rows written before, during and after a request-scoped provenance contribution still
     * form one valid chain — the contributed keys are hashed like explicit ones, and rows written
     * off the request thread are untouched. This is what keeps
     * {@code ACCESSFLOW_AUDIT_VERIFY_CHAIN_ON_STARTUP} green on an existing deployment.
     */
    @Test
    void chainStaysValidAcrossRowsWrittenWithAndWithoutTheProvenanceContributor() {
        var alice = UUID.randomUUID();
        var apiKeyId = UUID.randomUUID();
        record(0);
        record(1);

        // The real ServiceAccountProvenanceContributor reads the request attribute and the
        // security context — populate both on the test thread, as the filter would.
        var request = new MockHttpServletRequest();
        request.setAttribute(ApiKeyRequestFilter.ON_BEHALF_OF_ATTRIBUTE, alice);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        SecurityContextHolder.getContext().setAuthentication(new StubApiKeyToken(
                JwtClaims.forSystemRole(submitterId, "bot@example.com", UserRoleType.READONLY, organizationId),
                apiKeyId));
        try {
            record(2);
            record(3);
        } finally {
            RequestContextHolder.resetRequestAttributes();
            SecurityContextHolder.clearContext();
        }
        record(4);

        var result = auditLogService.verify(organizationId, null, null);
        assertThat(result.ok()).isTrue();
        assertThat(result.rowsChecked()).isEqualTo(5);

        var rows = auditLogService.query(organizationId, AuditLogQuery.empty(), PageRequest.of(0, 10))
                .content().stream()
                .sorted(java.util.Comparator.comparing(row -> (Integer) row.metadata().get("i")))
                .toList();
        assertThat(rows).hasSize(5);
        for (var row : rows) {
            var i = (Integer) row.metadata().get("i");
            if (i == 2 || i == 3) {
                assertThat(row.metadata())
                        .containsEntry("on_behalf_of_user_id", alice.toString())
                        .containsEntry("api_key_id", apiKeyId.toString())
                        .containsEntry("service_account", false);
            } else {
                assertThat(row.metadata()).doesNotContainKeys("on_behalf_of_user_id", "api_key_id",
                        "service_account");
            }
        }
    }

    private void record(int i) {
        auditLogService.record(new AuditEntry(
                AuditAction.QUERY_SUBMITTED,
                AuditResourceType.QUERY_REQUEST,
                queryRequestId,
                organizationId,
                submitterId,
                Map.of("i", i),
                "10.0.0.1",
                "ua"));
    }

    /** The real token is package-private in {@code security}; the marker interface is the contract. */
    private static final class StubApiKeyToken extends AbstractAuthenticationToken implements ApiKeyAuthentication {
        private final JwtClaims principal;
        private final UUID apiKeyId;

        StubApiKeyToken(JwtClaims principal, UUID apiKeyId) {
            super(List.of());
            this.principal = principal;
            this.apiKeyId = apiKeyId;
            setAuthenticated(true);
        }

        @Override
        public UUID apiKeyId() {
            return apiKeyId;
        }

        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public Object getPrincipal() {
            return principal;
        }
    }

    @Test
    void verifyReturnsOkForUntamperedChain() {
        for (int i = 0; i < 5; i++) {
            auditLogService.record(new AuditEntry(
                    AuditAction.QUERY_SUBMITTED,
                    AuditResourceType.QUERY_REQUEST,
                    queryRequestId,
                    organizationId,
                    submitterId,
                    Map.of("i", i),
                    "10.0.0.1",
                    "ua"));
        }

        var result = auditLogService.verify(organizationId, null, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.rowsChecked()).isEqualTo(5);
        assertThat(result.firstBadRowId()).isNull();
    }

    @Test
    void verifyReturnsOkForChainWrittenConcurrently() throws Exception {
        // Regression: record() used to capture created_at BEFORE acquiring the per-org
        // advisory lock, so concurrent writers could commit in lock order but with
        // inverted timestamps — forking the (created_at, id)-ordered chain and making
        // verify() report previous_hash_mismatch.
        var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 40; i++) {
                final int n = i;
                futures.add(executor.submit(() -> auditLogService.record(new AuditEntry(
                        AuditAction.QUERY_SUBMITTED,
                        AuditResourceType.QUERY_REQUEST,
                        queryRequestId,
                        organizationId,
                        submitterId,
                        Map.of("i", n),
                        "10.0.0.1",
                        "ua"))));
            }
            for (var f : futures) {
                f.get();
            }
        } finally {
            executor.shutdown();
        }

        var result = auditLogService.verify(organizationId, null, null);

        assertThat(result.ok())
                .withFailMessage("chain broke at row %s (%s)", result.firstBadRowId(),
                        result.firstBadReason())
                .isTrue();
        assertThat(result.rowsChecked()).isEqualTo(40);
    }

    @Test
    void verifyFlagsRowWhenMetadataIsTamperedWith() {
        UUID third = null;
        for (int i = 0; i < 5; i++) {
            var id = auditLogService.record(new AuditEntry(
                    AuditAction.QUERY_SUBMITTED,
                    AuditResourceType.QUERY_REQUEST,
                    queryRequestId,
                    organizationId,
                    submitterId,
                    Map.of("i", i),
                    null,
                    null));
            if (i == 2) {
                third = id;
            }
        }
        // Tamper with row 3 by rewriting its metadata directly in the DB.
        jdbcTemplate.update(
                "UPDATE audit_log SET metadata = ?::jsonb WHERE id = ?",
                "{\"tampered\":true}", third);

        var result = auditLogService.verify(organizationId, null, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.firstBadRowId()).isEqualTo(third);
        assertThat(result.firstBadReason()).isEqualTo("current_hash_mismatch");
    }

    @Test
    void verifyFlagsPreviousHashTamper() {
        UUID third = null;
        for (int i = 0; i < 5; i++) {
            var id = auditLogService.record(new AuditEntry(
                    AuditAction.QUERY_SUBMITTED,
                    AuditResourceType.QUERY_REQUEST,
                    queryRequestId,
                    organizationId,
                    submitterId,
                    Map.of("i", i),
                    null,
                    null));
            if (i == 2) {
                third = id;
            }
        }
        var corrupted = new byte[32];
        java.util.Arrays.fill(corrupted, (byte) 0x77);
        jdbcTemplate.update("UPDATE audit_log SET previous_hash = ? WHERE id = ?",
                corrupted, third);

        var result = auditLogService.verify(organizationId, null, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.firstBadRowId()).isEqualTo(third);
        assertThat(result.firstBadReason()).isEqualTo("previous_hash_mismatch");
    }

    @Test
    void verifyIsScopedToCallerOrganization() {
        auditLogService.record(new AuditEntry(
                AuditAction.QUERY_SUBMITTED,
                AuditResourceType.QUERY_REQUEST,
                queryRequestId,
                organizationId,
                submitterId,
                Map.of(),
                null,
                null));

        var otherOrg = UUID.randomUUID();
        var result = auditLogService.verify(otherOrg, null, null);
        assertThat(result.ok()).isTrue();
        assertThat(result.rowsChecked()).isZero();
    }
}
