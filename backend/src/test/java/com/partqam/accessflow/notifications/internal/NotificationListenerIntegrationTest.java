package com.partqam.accessflow.notifications.internal;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.CredentialEncryptionService;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.core.api.SslMode;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.events.AiAnalysisCompletedEvent;
import com.partqam.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.partqam.accessflow.core.internal.persistence.entity.ReviewPlanEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import com.partqam.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.partqam.accessflow.core.internal.persistence.repo.ReviewPlanRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import com.partqam.accessflow.notifications.api.NotificationChannelType;
import com.partqam.accessflow.notifications.internal.persistence.entity.NotificationChannelEntity;
import com.partqam.accessflow.notifications.internal.persistence.repo.NotificationChannelRepository;
import com.partqam.accessflow.workflow.events.QueryApprovedEvent;
import com.partqam.accessflow.workflow.events.QueryReadyForReviewEvent;
import com.sun.net.httpserver.HttpServer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class NotificationListenerIntegrationTest {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired ReviewPlanRepository reviewPlanRepository;
    @Autowired AiAnalysisRepository aiAnalysisRepository;
    @Autowired NotificationChannelRepository channelRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final AtomicReference<HttpServer> SERVER = new AtomicReference<>();
    private static final ConcurrentLinkedQueue<CapturedRequest> REQUESTS = new ConcurrentLinkedQueue<>();
    private static int serverPort;

    private UUID queryRequestId;
    private UUID organizationId;
    private UUID submitterId;
    private UUID datasourceId;
    private UUID channelId;

    @DynamicPropertySource
    static void env(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(((RSAPrivateCrtKey) kp.getPrivate()).getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        registry.add("accessflow.notifications.public-base-url", () -> "https://app.example.test");
        // Tighten retries so a failing send doesn't hold up the suite.
        registry.add("accessflow.notifications.retry.first", () -> "PT0.1S");
        registry.add("accessflow.notifications.retry.second", () -> "PT0.1S");
        registry.add("accessflow.notifications.retry.third", () -> "PT0.1S");

        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var bytes = exchange.getRequestBody().readAllBytes();
            REQUESTS.add(new CapturedRequest(
                    exchange.getRequestHeaders().getFirst("X-AccessFlow-Event"),
                    exchange.getRequestHeaders().getFirst("X-AccessFlow-Signature"),
                    exchange.getRequestHeaders().getFirst("X-AccessFlow-Delivery"),
                    new String(bytes, StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        SERVER.set(server);
        serverPort = server.getAddress().getPort();
    }

    @BeforeEach
    void setUp() {
        REQUESTS.clear();
        cleanup();
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary-" + UUID.randomUUID());
        org.setEdition(EditionType.COMMUNITY);
        organizationRepository.save(org);
        organizationId = org.getId();

        var submitter = new UserEntity();
        submitter.setId(UUID.randomUUID());
        submitter.setEmail("submitter@example.com");
        submitter.setDisplayName("Submitter");
        submitter.setPasswordHash("h");
        submitter.setRole(UserRoleType.ANALYST);
        submitter.setAuthProvider(AuthProviderType.LOCAL);
        submitter.setActive(true);
        submitter.setOrganization(org);
        userRepository.save(submitter);
        submitterId = submitter.getId();

        var admin = new UserEntity();
        admin.setId(UUID.randomUUID());
        admin.setEmail("admin@example.com");
        admin.setDisplayName("Admin");
        admin.setPasswordHash("h");
        admin.setRole(UserRoleType.ADMIN);
        admin.setAuthProvider(AuthProviderType.LOCAL);
        admin.setActive(true);
        admin.setOrganization(org);
        userRepository.save(admin);

        var channel = new NotificationChannelEntity();
        channel.setId(UUID.randomUUID());
        channel.setOrganizationId(org.getId());
        channel.setChannelType(NotificationChannelType.WEBHOOK);
        channel.setName("test-webhook");
        channel.setActive(true);
        channel.setConfigJson("{\"url\":\"http://127.0.0.1:" + serverPort + "/hook\","
                + "\"secret_encrypted\":\"" + encryptionService.encrypt("topsecret")
                + "\",\"timeout_seconds\":5}");
        channelRepository.save(channel);
        channelId = channel.getId();

        var plan = new ReviewPlanEntity();
        plan.setId(UUID.randomUUID());
        plan.setOrganization(org);
        plan.setName("default");
        plan.setRequiresAiReview(true);
        plan.setRequiresHumanApproval(true);
        plan.setMinApprovalsRequired(1);
        plan.setApprovalTimeoutHours(24);
        plan.setAutoApproveReads(false);
        plan.setNotifyChannels(new String[]{channel.getId().toString()});
        reviewPlanRepository.save(plan);

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
        ds.setRequireReviewWrites(true);
        ds.setReviewPlan(plan);
        ds.setAiAnalysisEnabled(true);
        ds.setActive(true);
        datasourceRepository.save(ds);
        datasourceId = ds.getId();

        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(ds);
        query.setSubmittedBy(submitter);
        query.setSqlText("UPDATE orders SET status = 'shipped' WHERE id = 1");
        query.setQueryType(QueryType.UPDATE);
        query.setStatus(QueryStatus.PENDING_REVIEW);
        queryRequestRepository.save(query);
        queryRequestId = query.getId();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("UPDATE query_requests SET ai_analysis_id = NULL");
        aiAnalysisRepository.deleteAll();
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
        reviewPlanRepository.deleteAll();
        channelRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void readyForReviewFiresWebhookWithSignature() {
        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                eventPublisher.publishEvent(new QueryReadyForReviewEvent(queryRequestId)));

        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    assertThat(REQUESTS).isNotEmpty();
                    var req = REQUESTS.peek();
                    assertThat(req.event()).isEqualTo("QUERY_SUBMITTED");
                    assertThat(req.signature()).startsWith("sha256=");
                    assertThat(req.delivery()).isNotBlank();
                    assertThat(req.body()).contains("\"event\":\"QUERY_SUBMITTED\"");
                    assertThat(req.body()).contains(queryRequestId.toString());
                });
    }

    @Test
    void approvedFiresWebhook() {
        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                eventPublisher.publishEvent(new QueryApprovedEvent(queryRequestId, UUID.randomUUID())));

        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    assertThat(REQUESTS).anyMatch(r -> "QUERY_APPROVED".equals(r.event()));
                });
    }

    @Test
    void aiCompletedFiresOnlyForCriticalRisk() {
        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                eventPublisher.publishEvent(new AiAnalysisCompletedEvent(
                        queryRequestId, UUID.randomUUID(), RiskLevel.LOW)));
        try {
            Thread.sleep(500);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        assertThat(REQUESTS).isEmpty();

        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                eventPublisher.publishEvent(new AiAnalysisCompletedEvent(
                        queryRequestId, UUID.randomUUID(), RiskLevel.CRITICAL)));
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(REQUESTS)
                        .anyMatch(r -> "AI_HIGH_RISK".equals(r.event())));
    }

    private record CapturedRequest(String event, String signature, String delivery, String body) {
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            var s = SERVER.get();
            if (s != null) {
                s.stop(0);
            }
        }));
    }
}
