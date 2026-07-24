package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.TestSystemRoleSeeder;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyDetectionService;
import com.bablsoft.accessflow.ai.api.BehaviorAnomalyStatus;
import com.bablsoft.accessflow.ai.internal.persistence.repo.BehaviorAnomalyRepository;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.events.AnomalyDetectedEvent;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
@Import(BehaviorAnomalyDetectionIntegrationTest.EventCollector.class)
class BehaviorAnomalyDetectionIntegrationTest {

    @Autowired BehaviorAnomalyDetectionService detectionService;
    @Autowired BehaviorAnomalyRepository anomalyRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EventCollector events;

    // A plain @EventListener bean collects AnomalyDetectedEvent synchronously — the orchestrator
    // publishes the event inside the detection call (no @ApplicationModuleListener AFTER_COMMIT
    // hop), so this is observable right after the call returns. AnomalyDetectedEvent is a POJO
    // event (not an ApplicationEvent subclass), so @EventListener is required rather than the
    // generic ApplicationListener<E extends ApplicationEvent> interface.
    @Component
    static class EventCollector {
        final List<AnomalyDetectedEvent> received = new CopyOnWriteArrayList<>();

        @EventListener
        void onAnomalyDetected(AnomalyDetectedEvent event) {
            received.add(event);
        }
    }

    private OrganizationEntity org;
    private UserEntity user;
    private DatasourceEntity datasource;

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
    }

    @BeforeEach
    void setUp() {
        cleanup();
        events.received.clear();

        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Org");
        org.setSlug("org-" + UUID.randomUUID());
        organizationRepository.save(org);

        user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("user-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("Behavior User");
        user.setPasswordHash("hash");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        userRepository.save(user);

        datasource = new DatasourceEntity();
        datasource.setId(UUID.randomUUID());
        datasource.setOrganization(org);
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
        datasource.setAiAnalysisEnabled(false);
        datasource.setActive(true);
        datasourceRepository.save(datasource);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.execute("TRUNCATE TABLE organizations CASCADE");
        TestSystemRoleSeeder.reseedSystemRoles(jdbcTemplate);
        jdbcTemplate.execute("TRUNCATE TABLE audit_log CASCADE");
    }

    @Test
    void buildsBaselineThenFlagsAnomalousWindowAndPublishesEvent() {
        // Baseline windows on consecutive DAYS at a fixed 10:00 hour: each has a steady 5 successful
        // SELECTs over `orders`. Keeping every window at the same hour-of-day (10) concentrates the
        // active-hour histogram on a single bucket, so only the scalar query_count varies — the
        // off-hours / novelty detectors stay quiet during baseline build-up. The orchestrator's
        // lookback is PT1H, so detection for `now` evaluates the most recently completed
        // [10:00, 11:00) window. Drive 8 windows to cross the minSampleSize (7) cold-start guard.
        var firstWindow = Instant.parse("2026-03-02T10:00:00Z");

        for (int day = 0; day < 8; day++) {
            var windowStart = firstWindow.plus(Duration.ofDays(day));
            seedQueryRows(windowStart, 5, "SELECT", true);
            detectionService.refreshAndDetectFor(org.getId(), user.getId(), datasource.getId(),
                    windowStart.plus(Duration.ofHours(1)).plusSeconds(30));
        }

        // No anomaly yet — the baseline is consistent (same hour, same volume, same table).
        assertThat(anomalyRepository.findByOrganizationIdAndUserIdAndStatus(
                org.getId(), user.getId(), BehaviorAnomalyStatus.OPEN)).isEmpty();

        // Anomalous window: a massive query-count spike, same hour/table so only query_count deviates.
        var anomalousWindowStart = firstWindow.plus(Duration.ofDays(8));
        seedQueryRows(anomalousWindowStart, 200, "SELECT", true);
        int detected = detectionService.refreshAndDetectFor(org.getId(), user.getId(),
                datasource.getId(), anomalousWindowStart.plus(Duration.ofHours(1)).plusSeconds(30));

        assertThat(detected).isGreaterThanOrEqualTo(1);

        var open = anomalyRepository.findByOrganizationIdAndUserIdAndStatus(
                org.getId(), user.getId(), BehaviorAnomalyStatus.OPEN);
        assertThat(open).isNotEmpty();
        assertThat(open).anyMatch(a -> a.getFeature().equals("query_count"));
        var queryCountAnomaly = open.stream()
                .filter(a -> a.getFeature().equals("query_count")).findFirst().orElseThrow();
        assertThat(queryCountAnomaly.getObservedValue()).isEqualTo(200.0);
        assertThat(queryCountAnomaly.getScore()).isGreaterThanOrEqualTo(3.0);
        assertThat(queryCountAnomaly.getWindowStart()).isEqualTo(anomalousWindowStart);

        // The detection event fired synchronously for the query_count anomaly.
        assertThat(events.received).anyMatch(e ->
                e.organizationId().equals(org.getId())
                        && e.userId().equals(user.getId())
                        && e.datasourceId().equals(datasource.getId())
                        && e.feature().equals("query_count"));
    }

    @Test
    void alreadyFoldedWindowIsNotReprocessed() {
        var windowStart = Instant.parse("2026-03-02T01:00:00Z");
        seedQueryRows(windowStart, 5, "SELECT", true);
        var now = windowStart.plus(Duration.ofHours(1)).plusSeconds(30);

        int first = detectionService.refreshAndDetectFor(
                org.getId(), user.getId(), datasource.getId(), now);
        int second = detectionService.refreshAndDetectFor(
                org.getId(), user.getId(), datasource.getId(), now);

        assertThat(first).isZero();   // first fold, below min sample size — no anomaly
        assertThat(second).isZero();  // window already folded — short-circuits
    }

    @Test
    void emptyWindowProducesNoAnomalyAndNoFold() {
        var windowStart = Instant.parse("2026-03-02T05:00:00Z");
        int detected = detectionService.refreshAndDetectFor(org.getId(), user.getId(),
                datasource.getId(), windowStart.plus(Duration.ofHours(1)).plusSeconds(30));
        assertThat(detected).isZero();
        assertThat(anomalyRepository.findByOrganizationIdAndUserIdAndStatus(
                org.getId(), user.getId(), BehaviorAnomalyStatus.OPEN)).isEmpty();
    }

    /** Insert {@code count} QUERY_EXECUTED/FAILED audit rows spread within the given hour window. */
    private void seedQueryRows(Instant windowStart, int count, String queryType, boolean success) {
        var action = success ? "QUERY_EXECUTED" : "QUERY_FAILED";
        for (int i = 0; i < count; i++) {
            // Spread rows over the first ~50 minutes so they all land inside [windowStart, +1h).
            var occurredAt = windowStart.plusSeconds((long) i * 600 % 3000 + 5L);
            var metadata = """
                    {"datasource_id":"%s","query_type":"%s","referenced_tables":["orders"],"rows_returned":7}
                    """.formatted(datasource.getId(), queryType);
            insertAuditRow(occurredAt, action, metadata);
        }
    }

    private void insertAuditRow(Instant createdAt, String action, String metadataJson) {
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO audit_log "
                            + "(id, organization_id, actor_id, action, resource_type, resource_id, "
                            + " metadata, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)");
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, org.getId());
            ps.setObject(3, user.getId());
            ps.setString(4, action);
            ps.setString(5, "query_request");
            ps.setNull(6, Types.OTHER);
            ps.setString(7, metadataJson);
            ps.setTimestamp(8, Timestamp.from(createdAt));
            return ps;
        });
    }
}
