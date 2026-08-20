package com.bablsoft.accessflow.audit.internal.sink;

import com.bablsoft.accessflow.audit.api.AuditSinkType;
import com.bablsoft.accessflow.audit.internal.codec.AuditSinkConfigCodec;
import com.bablsoft.accessflow.audit.internal.codec.S3ObjectLockSinkConfig;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditSinkEntity;
import com.bablsoft.accessflow.core.api.ContentSigner;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3ObjectLockSinkDelivererTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
    private static final Instant FIRST_AT = Instant.parse("2026-08-19T10:15:30.123456Z");
    private static final Instant LAST_AT = Instant.parse("2026-08-19T10:16:31.654321Z");
    private static final UUID ORG_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID LAST_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AuditSinkConfigCodec codec =
            new AuditSinkConfigCodec(mapper, new ReversibleEncryption());
    private final AuditExportEventWriter eventWriter = new AuditExportEventWriter(mapper);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock S3ClientFactory clientFactory;
    @Mock S3Client s3Client;
    @Mock ContentSigner contentSigner;

    private S3ObjectLockSinkDeliverer deliverer;

    @BeforeEach
    void setUp() {
        deliverer = new S3ObjectLockSinkDeliverer(
                codec, eventWriter, clientFactory, contentSigner, clock);
        lenient().when(clientFactory.create(any(S3ObjectLockSinkConfig.class)))
                .thenReturn(s3Client);
        lenient().when(contentSigner.sign(any(byte[].class))).thenReturn("c2lnbmF0dXJl");
        lenient().when(contentSigner.algorithm()).thenReturn("SHA256withRSA");
    }

    private AuditSinkEntity sink(Map<String, Object> overrides) {
        var config = new HashMap<String, Object>(Map.of(
                "bucket", "audit-worm",
                "region", "eu-west-1",
                "access_key_id", "AKIA123",
                "secret_access_key", "s3-secret",
                "retention_days", 30));
        config.putAll(overrides);
        var sink = new AuditSinkEntity();
        sink.setId(UUID.randomUUID());
        sink.setOrganizationId(ORG_ID);
        sink.setName("s3");
        sink.setType(AuditSinkType.S3_OBJECT_LOCK);
        sink.setConfigJson(codec.encodeForPersistence(AuditSinkType.S3_OBJECT_LOCK, config));
        return sink;
    }

    private static AuditExportEvent event(UUID id, Instant createdAt, String currentHash) {
        return new AuditExportEvent(id, ORG_ID, UUID.randomUUID(), "QUERY_SUBMITTED",
                "query_request", UUID.randomUUID(), "{}", "203.0.113.5", "ua/1", createdAt,
                "aa11", currentHash);
    }

    private List<AuditExportEvent> batch() {
        return List.of(
                event(UUID.randomUUID(), FIRST_AT, "cafe01"),
                event(LAST_ID, LAST_AT, "cafe02"));
    }

    @Test
    void typeIsS3ObjectLock() {
        assertThat(deliverer.type()).isEqualTo(AuditSinkType.S3_OBJECT_LOCK);
    }

    @Test
    void uploadsLockedSegmentAndSignatureSidecar() {
        var requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        var bodies = ArgumentCaptor.forClass(RequestBody.class);
        when(s3Client.putObject(requests.capture(), bodies.capture())).thenReturn(null);
        var batch = batch();

        deliverer.deliver(sink(Map.of()), batch);

        assertThat(requests.getAllValues()).hasSize(2);
        var segment = requests.getAllValues().get(0);
        var sidecar = requests.getAllValues().get(1);

        var expectedKey = "audit/2026/08/19/audit-" + ORG_ID
                + "-20260819T101530.123456-20260819T101631.654321-" + LAST_ID + ".jsonl";
        assertThat(segment.bucket()).isEqualTo("audit-worm");
        assertThat(segment.key()).isEqualTo(expectedKey);
        assertThat(segment.contentType()).isEqualTo("application/x-ndjson");
        assertThat(segment.objectLockMode()).isEqualTo(ObjectLockMode.COMPLIANCE);
        assertThat(segment.objectLockRetainUntilDate())
                .isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(segment.metadata())
                .containsEntry("chain-head", "cafe02")
                .containsEntry("signature-algorithm", "SHA256withRSA");

        assertThat(sidecar.bucket()).isEqualTo("audit-worm");
        assertThat(sidecar.key()).isEqualTo(expectedKey + ".sig");
        assertThat(sidecar.contentType()).isEqualTo("text/plain");
        assertThat(sidecar.objectLockMode()).isEqualTo(ObjectLockMode.COMPLIANCE);
        assertThat(sidecar.objectLockRetainUntilDate())
                .isEqualTo(NOW.plus(Duration.ofDays(30)));

        var segmentBytes = bytesOf(bodies.getAllValues().get(0));
        assertThat(new String(segmentBytes, StandardCharsets.UTF_8))
                .isEqualTo(eventWriter.toJsonLines(batch));
        assertThat(bytesOf(bodies.getAllValues().get(1)))
                .isEqualTo("c2lnbmF0dXJl".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void governanceRetentionModeMapsToGovernanceLock() {
        var requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        when(s3Client.putObject(requests.capture(), any(RequestBody.class))).thenReturn(null);

        deliverer.deliver(sink(Map.of("retention_mode", "GOVERNANCE")), batch());

        assertThat(requests.getAllValues())
                .allSatisfy(r -> assertThat(r.objectLockMode())
                        .isEqualTo(ObjectLockMode.GOVERNANCE));
    }

    @Test
    void nullChainHeadUploadsEmptyMetadataValue() {
        var requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        when(s3Client.putObject(requests.capture(), any(RequestBody.class))).thenReturn(null);

        deliverer.deliver(sink(Map.of()),
                List.of(event(LAST_ID, FIRST_AT, null)));

        assertThat(requests.getAllValues().get(0).metadata()).containsEntry("chain-head", "");
    }

    @Test
    void fullBatchIsAlwaysReady() {
        assertThat(deliverer.readyToDeliver(sink(Map.of()), batch(), 2, NOW)).isTrue();
    }

    @Test
    void partialBatchYoungerThanSegmentMaxAgeHoldsBack() {
        var young = List.of(event(LAST_ID, NOW.minus(Duration.ofMinutes(5)), "aa"));

        assertThat(deliverer.readyToDeliver(sink(Map.of()), young, 500, NOW)).isFalse();
    }

    @Test
    void partialBatchOlderThanSegmentMaxAgeIsReady() {
        // Default segment_max_age is PT15M.
        var stale = List.of(event(LAST_ID, NOW.minus(Duration.ofMinutes(16)), "aa"));

        assertThat(deliverer.readyToDeliver(sink(Map.of()), stale, 500, NOW)).isTrue();
    }

    @Test
    void deliverTestUploadsUnlockedObjectUnderTestPrefix() {
        var requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        when(s3Client.putObject(requests.capture(), any(RequestBody.class))).thenReturn(null);

        deliverer.deliverTest(sink(Map.of()), event(UUID.randomUUID(), NOW, "aa"));

        assertThat(requests.getAllValues()).hasSize(1);
        var request = requests.getValue();
        assertThat(request.bucket()).isEqualTo("audit-worm");
        assertThat(request.key()).startsWith("audit/test/audit-test-").endsWith(".jsonl");
        assertThat(request.contentType()).isEqualTo("application/x-ndjson");
        assertThat(request.objectLockMode()).isNull();
        assertThat(request.objectLockRetainUntilDate()).isNull();
    }

    @Test
    void sdkExceptionOnDeliverBecomesDeliveryException() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkException.builder().message("access denied").build());

        assertThatThrownBy(() -> deliverer.deliver(sink(Map.of()), batch()))
                .isInstanceOf(AuditSinkDeliveryException.class)
                .hasMessageContaining("audit-worm");
    }

    @Test
    void sdkExceptionOnTestBecomesDeliveryException() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkException.builder().message("access denied").build());

        assertThatThrownBy(() -> deliverer.deliverTest(
                sink(Map.of()), event(UUID.randomUUID(), NOW, "aa")))
                .isInstanceOf(AuditSinkDeliveryException.class)
                .hasMessageContaining("test upload");
    }

    private static byte[] bytesOf(RequestBody body) {
        try (var stream = body.contentStreamProvider().newStream()) {
            return stream.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static final class ReversibleEncryption implements CredentialEncryptionService {
        @Override
        public String encrypt(String plaintext) {
            return "enc:" + plaintext;
        }

        @Override
        public String decrypt(String ciphertext) {
            return ciphertext.substring("enc:".length());
        }
    }
}
