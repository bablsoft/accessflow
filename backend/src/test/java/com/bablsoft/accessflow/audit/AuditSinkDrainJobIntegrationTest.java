package com.bablsoft.accessflow.audit;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.AuditSinkType;
import com.bablsoft.accessflow.audit.internal.codec.AuditSinkConfigCodec;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditSinkEntity;
import com.bablsoft.accessflow.audit.internal.config.AuditSinkProperties;
import com.bablsoft.accessflow.audit.internal.persistence.repo.AuditLogRepository;
import com.bablsoft.accessflow.audit.internal.persistence.repo.AuditSinkRepository;
import com.bablsoft.accessflow.audit.internal.sink.AuditExportEventWriter;
import com.bablsoft.accessflow.audit.internal.sink.AuditSinkDeliverer;
import com.bablsoft.accessflow.audit.internal.sink.AuditSinkDrainService;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end drain over a real Postgres audit log and a real in-process HTTPS_BATCH receiver
 * (#628): signed delivery, keyset cursor advance, failure backoff without cursor movement, and
 * at-least-once recovery with no replay of already-delivered rows.
 *
 * <p>{@code audit_log} is INSERT-only, so isolation comes from a fresh organization UUID per
 * run, never from cleanup.
 */
@SpringBootTest(properties = {
        // Keep the background AuditSinkDrainJob out of the way: this test drives
        // AuditSinkDrainService.drainAll(...) explicitly.
        "accessflow.audit.sinks.drain-interval=PT1H",
        "accessflow.encryption-key=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"})
@ImportTestcontainers(TestcontainersConfig.class)
class AuditSinkDrainJobIntegrationTest {

    private static final String SECRET = "it-hmac-secret";

    @Autowired AuditLogService auditLogService;
    @Autowired AuditSinkDrainService drainService;
    @Autowired AuditSinkRepository sinkRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired AuditSinkConfigCodec codec;
    @Autowired AuditExportEventWriter eventWriter;
    @Autowired List<AuditSinkDeliverer> deliverers;
    @Autowired StringRedisTemplate redisTemplate;

    /**
     * The ShedLock key the background {@code AuditSinkDrainJob} takes before draining. This test
     * drives {@code drainAll} directly, but the surefire run keeps earlier Spring contexts cached
     * — each with its own live drain job on the shared database — and one of their ticks can
     * drain this test's sink first. Holding the lock for the test's duration makes every
     * background tick skip, exactly as a second cluster node would.
     */
    private static final String DRAIN_JOB_LOCK_KEY =
            "job-lock:accessflow:shedlock:auditSinkDrainJob";

    private final JsonMapper mapper = JsonMapper.builder().build();

    private UUID organizationId;
    private UUID sinkId;
    private HttpServer server;
    private final List<ReceivedRequest> received =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    /** Forced-200 responses remaining before {@link #responseStatus} applies; lets a tick succeed-then-fail. */
    private final AtomicInteger successBudget = new AtomicInteger(0);

    private record ReceivedRequest(String signature, String event, String delivery, byte[] body) {
    }

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
    }

    @BeforeEach
    void setUp() throws Exception {
        redisTemplate.opsForValue().set(DRAIN_JOB_LOCK_KEY, "held-by-integration-test",
                java.time.Duration.ofMinutes(10));

        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Sink Org");
        org.setSlug("sink-org-" + UUID.randomUUID());
        organizationRepository.save(org);
        organizationId = org.getId();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/audit", exchange -> {
            var body = exchange.getRequestBody().readAllBytes();
            received.add(new ReceivedRequest(
                    exchange.getRequestHeaders().getFirst("X-AccessFlow-Signature"),
                    exchange.getRequestHeaders().getFirst("X-AccessFlow-Event"),
                    exchange.getRequestHeaders().getFirst("X-AccessFlow-Delivery"),
                    body));
            int status = successBudget.getAndDecrement() > 0 ? 200 : responseStatus.get();
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        successBudget.set(0);
        responseStatus.set(200);
        received.clear();

        var sink = new AuditSinkEntity();
        sink.setId(UUID.randomUUID());
        sink.setOrganizationId(organizationId);
        sink.setName("it-https-sink");
        sink.setType(AuditSinkType.HTTPS_BATCH);
        sink.setConfigJson(codec.encodeForPersistence(AuditSinkType.HTTPS_BATCH, Map.of(
                "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/audit",
                "secret", SECRET)));
        sinkRepository.save(sink);
        sinkId = sink.getId();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        // Re-read before deleting: a pre-loaded @Version entity trips the optimistic lock.
        sinkRepository.findById(sinkId).ifPresent(sinkRepository::delete);
        redisTemplate.delete(DRAIN_JOB_LOCK_KEY);
    }

    @Test
    void drainsSignedBatchesAdvancesCursorAndRecoversAfterFailure() {
        record3AuditRows();
        var backlog = auditLogRepository.findAfterKeyset(organizationId, Instant.EPOCH,
                AuditSinkEntity.CURSOR_ID_FLOOR, PageRequest.of(0, 100));
        assertThat(backlog).hasSize(3);
        var lastRow = backlog.get(2);

        // --- 1. Healthy drain: one POST with all three rows, HMAC-signed, cursor advanced.
        var now1 = Instant.now();
        assertThat(drainService.drainAll(now1)).isEqualTo(1);

        assertThat(received).hasSize(1);
        var first = received.get(0);
        assertThat(first.event()).isEqualTo("audit.batch");
        assertThat(first.delivery()).isNotBlank();
        assertThat(first.signature()).isEqualTo("sha256=" + hmacHex(first.body(), SECRET));

        var array = mapper.readTree(new String(first.body(), StandardCharsets.UTF_8));
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat(array.get(i).get("id").asString())
                    .isEqualTo(backlog.get(i).getId().toString());
            assertThat(array.get(i).get("organization_id").asString())
                    .isEqualTo(organizationId.toString());
            assertThat(array.get(i).get("current_hash").asString()).isNotBlank();
        }

        var afterSuccess = sinkRepository.findById(sinkId).orElseThrow();
        assertThat(afterSuccess.getCursorCreatedAt()).isEqualTo(lastRow.getCreatedAt());
        assertThat(afterSuccess.getCursorId()).isEqualTo(lastRow.getId());
        assertThat(afterSuccess.getLastSuccessAt()).isNotNull();
        assertThat(afterSuccess.getConsecutiveFailures()).isZero();
        assertThat(afterSuccess.getLastError()).isNull();

        // --- 2. Receiver failure: error recorded, backoff set, cursor NOT advanced.
        responseStatus.set(500);
        var fourthRowId = recordOneAuditRow();
        var now2 = Instant.now();
        assertThat(drainService.drainAll(now2)).isZero();

        var afterFailure = sinkRepository.findById(sinkId).orElseThrow();
        assertThat(afterFailure.getConsecutiveFailures()).isEqualTo(1);
        assertThat(afterFailure.getLastError()).isNotBlank();
        // Tolerate the DB's microsecond truncation of the stored instant.
        assertThat(afterFailure.getNextAttemptAt()).isBetween(
                now2.plusSeconds(30).minusMillis(1), now2.plusSeconds(30).plusMillis(1));
        assertThat(afterFailure.getCursorCreatedAt()).isEqualTo(lastRow.getCreatedAt());
        assertThat(afterFailure.getCursorId()).isEqualTo(lastRow.getId());

        // --- 3. Recovery past the backoff: the failed row is delivered exactly once more,
        // and the first three are never replayed (at-least-once, cursor-bounded).
        responseStatus.set(200);
        assertThat(drainService.drainAll(now2.plusSeconds(31))).isEqualTo(1);

        assertThat(received).hasSize(3);
        var recovery = received.get(2);
        assertThat(recovery.signature()).isEqualTo("sha256=" + hmacHex(recovery.body(), SECRET));
        var recoveredArray = mapper.readTree(new String(recovery.body(), StandardCharsets.UTF_8));
        assertThat(recoveredArray.size()).isEqualTo(1);
        assertThat(recoveredArray.get(0).get("id").asString())
                .isEqualTo(fourthRowId.toString());

        var afterRecovery = sinkRepository.findById(sinkId).orElseThrow();
        assertThat(afterRecovery.getConsecutiveFailures()).isZero();
        assertThat(afterRecovery.getNextAttemptAt()).isNull();
        assertThat(afterRecovery.getLastError()).isNull();
        assertThat(afterRecovery.getCursorId()).isEqualTo(fourthRowId);
    }

    /**
     * A tick that spans several batches must commit every cursor advance: the loaded sink row is
     * detached and {@code @Version}-locked, so each save has to adopt the merged instance — the
     * regression here was a {@code StaleObjectStateException} on the second batch of every tick.
     * Runs against the real DB with a batch size of 2 via a locally-built drain service.
     */
    @Test
    void multiBatchTickCommitsEveryCursorAdvanceAndRecordsMidTickFailure() {
        var tinyBatches = new AuditSinkDrainService(sinkRepository, auditLogRepository,
                eventWriter, new AuditSinkProperties(null, 2, 5), deliverers);

        // --- 1. 5 rows / batchSize 2 -> three deliveries and three cursor commits in ONE tick.
        recordAuditRows(5);
        var backlog = auditLogRepository.findAfterKeyset(organizationId, Instant.EPOCH,
                AuditSinkEntity.CURSOR_ID_FLOOR, PageRequest.of(0, 100));
        assertThat(tinyBatches.drainAll(Instant.now())).isEqualTo(1);

        assertThat(received).hasSize(3);
        assertThat(received.stream()
                .map(r -> mapper.readTree(new String(r.body(), StandardCharsets.UTF_8)).size()))
                .containsExactly(2, 2, 1);
        var afterMultiBatch = sinkRepository.findById(sinkId).orElseThrow();
        assertThat(afterMultiBatch.getCursorId()).isEqualTo(backlog.get(4).getId());
        assertThat(afterMultiBatch.getConsecutiveFailures()).isZero();

        // --- 2. Success-then-failure in the same tick: the failure must land on the freshly
        // merged row, recording backoff instead of tripping the optimistic lock and losing it.
        recordAuditRows(3);
        successBudget.set(1);
        responseStatus.set(500);
        var now = Instant.now();
        assertThat(tinyBatches.drainAll(now)).isEqualTo(1);

        assertThat(received).hasSize(5);
        var afterMixedTick = sinkRepository.findById(sinkId).orElseThrow();
        assertThat(afterMixedTick.getConsecutiveFailures()).isEqualTo(1);
        assertThat(afterMixedTick.getLastError()).isNotBlank();
        assertThat(afterMixedTick.getNextAttemptAt()).isNotNull();
        // Cursor sits after the one successful batch of this tick (rows 6-7), not further.
        var all = auditLogRepository.findAfterKeyset(organizationId, Instant.EPOCH,
                AuditSinkEntity.CURSOR_ID_FLOOR, PageRequest.of(0, 100));
        assertThat(afterMixedTick.getCursorId()).isEqualTo(all.get(6).getId());
    }

    private void record3AuditRows() {
        recordAuditRows(3);
    }

    private void recordAuditRows(int count) {
        for (int i = 0; i < count; i++) {
            auditLogService.record(new AuditEntry(
                    AuditAction.USER_LOGIN,
                    AuditResourceType.USER,
                    UUID.randomUUID(),
                    organizationId,
                    null,
                    Map.of("seq", i),
                    "127.0.0.1",
                    "it/1"));
        }
    }

    private UUID recordOneAuditRow() {
        auditLogService.record(new AuditEntry(
                AuditAction.USER_LOGIN,
                AuditResourceType.USER,
                UUID.randomUUID(),
                organizationId,
                null,
                Map.of("seq", 3),
                "127.0.0.1",
                "it/1"));
        var all = auditLogRepository.findAfterKeyset(organizationId, Instant.EPOCH,
                AuditSinkEntity.CURSOR_ID_FLOOR, PageRequest.of(0, 100));
        return all.stream()
                .map(AuditLogEntity::getId)
                .reduce((a, b) -> b)
                .orElseThrow();
    }

    private static String hmacHex(byte[] body, String secret) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
