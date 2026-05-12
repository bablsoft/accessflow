package com.partqam.accessflow.realtime.internal;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.CredentialEncryptionService;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.DecisionType;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.SslMode;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.api.UserView;
import com.partqam.accessflow.core.events.QueryReadyForReviewEvent;
import com.partqam.accessflow.core.events.QueryStatusChangedEvent;
import com.partqam.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.partqam.accessflow.core.internal.persistence.entity.ReviewPlanApproverEntity;
import com.partqam.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.partqam.accessflow.core.internal.persistence.repo.ReviewPlanApproverRepository;
import com.partqam.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import com.partqam.accessflow.security.internal.jwt.JwtService;
import com.partqam.accessflow.workflow.events.ReviewDecisionMadeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ImportTestcontainers(TestcontainersConfig.class)
class RealtimeWebSocketIntegrationTest {

    @LocalServerPort int port;

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired ReviewPlanRepository reviewPlanRepository;
    @Autowired ReviewPlanApproverRepository reviewPlanApproverRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired ObjectMapper objectMapper;
    @Autowired PlatformTransactionManager transactionManager;

    private final BlockingQueue<String> received = new ArrayBlockingQueue<>(8);
    private WebSocketSession session;

    @DynamicPropertySource
    static void rsaProperties(DynamicPropertyRegistry registry) throws Exception {
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

    @AfterEach
    void cleanup() throws Exception {
        if (session != null && session.isOpen()) {
            session.close(CloseStatus.NORMAL);
        }
        // Order matters: child rows before parents to satisfy FK constraints.
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
        reviewPlanApproverRepository.deleteAll();
        reviewPlanRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void rejectsHandshakeWhenTokenIsMissing() {
        var client = new StandardWebSocketClient();
        var future = client.execute(silentHandler(),
                "ws://localhost:" + port + "/ws");

        // Connection attempt should fail (no token).
        assertThat(future).failsWithin(java.time.Duration.ofSeconds(5));
    }

    @Test
    void deliversStatusChangedEnvelopeToConnectedSubmitter() throws Exception {
        var user = persistUser();
        var token = jwtService.generateAccessToken(toUserView(user));

        session = openSession(token);

        // Wait briefly for the SessionRegistry to register us.
        TimeUnit.MILLISECONDS.sleep(200);

        // ApplicationModuleListener fires AFTER_COMMIT, so the event must be published from
        // inside a real transaction or it will be silently dropped.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publishEvent(new QueryStatusChangedEvent(
                        UUID.randomUUID(), user.getId(),
                        QueryStatus.PENDING_AI, QueryStatus.PENDING_REVIEW)));

        var raw = received.poll(5, TimeUnit.SECONDS);
        assertThat(raw).as("WS message should arrive within 5 s").isNotNull();
        var envelope = objectMapper.readTree(raw);
        assertThat(envelope.get("event").asText()).isEqualTo("query.status_changed");
        assertThat(envelope.get("data").get("old_status").asText()).isEqualTo("PENDING_AI");
        assertThat(envelope.get("data").get("new_status").asText()).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void deliversReviewNewRequestEnvelopeToEligibleReviewer() throws Exception {
        var org = persistOrganization();
        var submitter = persistUser(org, "submitter-" + UUID.randomUUID() + "@example.com",
                UserRoleType.ANALYST);
        var reviewer = persistUser(org, "reviewer-" + UUID.randomUUID() + "@example.com",
                UserRoleType.REVIEWER);
        var plan = persistReviewPlan(org);
        persistReviewPlanApprover(plan, reviewer, 1);
        var datasource = persistDatasource(org, plan);
        var query = persistQueryRequest(datasource, submitter);
        var token = jwtService.generateAccessToken(toUserView(reviewer));

        session = openSession(token);

        TimeUnit.MILLISECONDS.sleep(200);

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publishEvent(new QueryReadyForReviewEvent(query.getId())));

        // NotificationListener also reacts to QueryReadyForReviewEvent and may push a
        // `notification.created` envelope on the same session; pick the review one.
        var envelope = pollForEvent("review.new_request", 5);
        assertThat(envelope).as("review.new_request should arrive within 5 s").isNotNull();
        var data = envelope.get("data");
        assertThat(data.get("query_id").asText()).isEqualTo(query.getId().toString());
        // No AI analysis persisted — spec allows risk_level to be null in that case.
        assertThat(data.get("risk_level").isNull()).isTrue();
        assertThat(data.get("submitter").asText()).isEqualTo(submitter.getEmail());
        assertThat(data.get("datasource").asText()).isEqualTo(datasource.getName());
    }

    @Test
    void deliversReviewDecisionMadeEnvelopeToSubmitter() throws Exception {
        var org = persistOrganization();
        var submitter = persistUser(org, "submitter-" + UUID.randomUUID() + "@example.com",
                UserRoleType.ANALYST);
        var reviewer = persistUser(org, "reviewer-" + UUID.randomUUID() + "@example.com",
                UserRoleType.REVIEWER);
        var token = jwtService.generateAccessToken(toUserView(submitter));

        session = openSession(token);

        TimeUnit.MILLISECONDS.sleep(200);

        var queryId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publishEvent(new ReviewDecisionMadeEvent(
                        queryId, submitter.getId(), reviewer.getId(),
                        DecisionType.APPROVED, "looks good")));

        var envelope = pollForEvent("review.decision_made", 5);
        assertThat(envelope).as("review.decision_made should arrive within 5 s").isNotNull();
        var data = envelope.get("data");
        assertThat(data.get("query_id").asText()).isEqualTo(queryId.toString());
        assertThat(data.get("decision").asText()).isEqualTo("APPROVED");
        assertThat(data.get("reviewer").asText()).isEqualTo(reviewer.getEmail());
        assertThat(data.get("comment").asText()).isEqualTo("looks good");
    }

    private WebSocketSession openSession(String token) throws Exception {
        var client = new StandardWebSocketClient();
        var future = client.execute(new TextHandler(),
                "ws://localhost:" + port + "/ws?token=" + token);
        return future.get(5, TimeUnit.SECONDS);
    }

    private JsonNode pollForEvent(String expectedEvent, long timeoutSeconds) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            var remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return null;
            }
            var raw = received.poll(remainingNanos, TimeUnit.NANOSECONDS);
            if (raw == null) {
                return null;
            }
            var envelope = objectMapper.readTree(raw);
            if (expectedEvent.equals(envelope.get("event").asText())) {
                return envelope;
            }
        }
        return null;
    }

    private UserEntity persistUser() {
        var org = persistOrganization();
        return persistUser(org, "ws-test-" + UUID.randomUUID() + "@example.com",
                UserRoleType.ANALYST);
    }

    private OrganizationEntity persistOrganization() {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("WS Test Org");
        org.setSlug("ws-test-" + UUID.randomUUID());
        org.setEdition(EditionType.COMMUNITY);
        return organizationRepository.save(org);
    }

    private UserEntity persistUser(OrganizationEntity org, String email, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setOrganization(org);
        user.setEmail(email);
        user.setDisplayName("WS " + role.name());
        user.setRole(role);
        user.setActive(true);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setPasswordHash("not-used");
        return userRepository.save(user);
    }

    private ReviewPlanEntity persistReviewPlan(OrganizationEntity org) {
        var plan = new ReviewPlanEntity();
        plan.setId(UUID.randomUUID());
        plan.setOrganization(org);
        plan.setName("default");
        plan.setRequiresAiReview(false);
        plan.setRequiresHumanApproval(true);
        plan.setMinApprovalsRequired(1);
        plan.setApprovalTimeoutHours(24);
        plan.setAutoApproveReads(false);
        return reviewPlanRepository.save(plan);
    }

    private void persistReviewPlanApprover(ReviewPlanEntity plan, UserEntity user, int stage) {
        var approver = new ReviewPlanApproverEntity();
        approver.setId(UUID.randomUUID());
        approver.setReviewPlan(plan);
        approver.setUser(user);
        approver.setStage(stage);
        reviewPlanApproverRepository.save(approver);
    }

    private DatasourceEntity persistDatasource(OrganizationEntity org, ReviewPlanEntity plan) {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
        ds.setName("orders-prod");
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
        ds.setRequireReviewWrites(true);
        ds.setReviewPlan(plan);
        ds.setAiAnalysisEnabled(false);
        ds.setActive(true);
        return datasourceRepository.save(ds);
    }

    private QueryRequestEntity persistQueryRequest(DatasourceEntity ds, UserEntity submitter) {
        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(ds);
        query.setSubmittedBy(submitter);
        query.setSqlText("SELECT 1");
        query.setQueryType(QueryType.SELECT);
        query.setStatus(QueryStatus.PENDING_REVIEW);
        return queryRequestRepository.save(query);
    }

    private static UserView toUserView(UserEntity user) {
        return new UserView(user.getId(), user.getEmail(), user.getDisplayName(),
                user.getRole(), user.getOrganization().getId(), user.isActive(),
                user.getAuthProvider(), user.getPasswordHash(),
                null, user.getPreferredLanguage(), user.isTotpEnabled(), Instant.now());
    }

    private WebSocketHandler silentHandler() {
        return new AbstractWebSocketHandler() {};
    }

    private final class TextHandler extends AbstractWebSocketHandler {
        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            received.offer(message.getPayload());
        }
    }
}
