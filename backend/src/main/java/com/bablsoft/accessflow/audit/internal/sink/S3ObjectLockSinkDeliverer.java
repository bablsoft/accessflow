package com.bablsoft.accessflow.audit.internal.sink;

import com.bablsoft.accessflow.audit.api.AuditSinkType;
import com.bablsoft.accessflow.audit.internal.codec.AuditSinkConfigCodec;
import com.bablsoft.accessflow.audit.internal.codec.S3ObjectLockSinkConfig;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditSinkEntity;
import com.bablsoft.accessflow.core.api.ContentSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WORM archival sink (#628): each batch becomes a JSONL segment uploaded under an S3 Object
 * Lock retention, plus a sibling {@code .sig} object carrying the deployment signature of the
 * segment bytes. The segment's last line holds the organization's chain head
 * ({@code current_hash}) at upload time, so the signature covers the chain head — the segment
 * verifies offline against {@code GET /admin/compliance/signing-certificate}.
 *
 * <p>Unlike the streaming sinks, this one holds back partial batches until the oldest pending
 * row exceeds the configured {@code segment_max_age}, keeping segments chunky instead of
 * writing one tiny locked object per drain tick.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class S3ObjectLockSinkDeliverer implements AuditSinkDeliverer {

    static final String METADATA_CHAIN_HEAD = "chain-head";
    static final String METADATA_SIGNATURE_ALGORITHM = "signature-algorithm";

    private static final DateTimeFormatter DAY_PATH =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter KEY_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSSSSS").withZone(ZoneOffset.UTC);

    private final AuditSinkConfigCodec codec;
    private final AuditExportEventWriter eventWriter;
    private final S3ClientFactory clientFactory;
    private final ContentSigner contentSigner;
    private final Clock clock;

    @Override
    public AuditSinkType type() {
        return AuditSinkType.S3_OBJECT_LOCK;
    }

    @Override
    public boolean readyToDeliver(AuditSinkEntity sink, List<AuditExportEvent> batch,
                                  int fullBatchSize, Instant now) {
        if (batch.size() >= fullBatchSize) {
            return true;
        }
        var config = codec.decodeS3ObjectLock(sink.getConfigJson());
        var oldest = batch.getFirst().createdAt();
        return Duration.between(oldest, now).compareTo(config.segmentMaxAge()) >= 0;
    }

    @Override
    public void deliver(AuditSinkEntity sink, List<AuditExportEvent> batch) {
        var config = codec.decodeS3ObjectLock(sink.getConfigJson());
        var segment = eventWriter.toJsonLines(batch).getBytes(StandardCharsets.UTF_8);
        var chainHead = batch.getLast().currentHash();
        var key = segmentKey(config, batch);
        var retainUntil = clock.instant().plus(Duration.ofDays(config.retentionDays()));
        try (var client = clientFactory.create(config)) {
            putLocked(client, config, key, segment, chainHead, retainUntil);
            var signature = contentSigner.sign(segment).getBytes(StandardCharsets.UTF_8);
            putLocked(client, config, key + ".sig", signature, chainHead, retainUntil);
        } catch (SdkException ex) {
            throw new AuditSinkDeliveryException(
                    "S3 Object Lock upload to bucket '" + config.bucket() + "' failed: "
                            + ex.getMessage(), ex);
        }
    }

    @Override
    public void deliverTest(AuditSinkEntity sink, AuditExportEvent event) {
        var config = codec.decodeS3ObjectLock(sink.getConfigJson());
        var body = eventWriter.toJsonLines(List.of(event)).getBytes(StandardCharsets.UTF_8);
        var key = config.prefix() + "test/audit-test-" + UUID.randomUUID() + ".jsonl";
        try (var client = clientFactory.create(config)) {
            // No retention lock on the test object — a connectivity probe must not create a
            // year-long immutable artifact.
            client.putObject(PutObjectRequest.builder()
                            .bucket(config.bucket())
                            .key(key)
                            .contentType("application/x-ndjson")
                            .build(),
                    RequestBody.fromBytes(body));
        } catch (SdkException ex) {
            throw new AuditSinkDeliveryException(
                    "S3 Object Lock test upload to bucket '" + config.bucket() + "' failed: "
                            + ex.getMessage(), ex);
        }
    }

    private void putLocked(S3Client client, S3ObjectLockSinkConfig config, String key,
                           byte[] body, String chainHead, Instant retainUntil) {
        var request = PutObjectRequest.builder()
                .bucket(config.bucket())
                .key(key)
                .contentType(key.endsWith(".sig") ? "text/plain" : "application/x-ndjson")
                .objectLockMode(config.retentionMode() == S3ObjectLockSinkConfig.RetentionMode.GOVERNANCE
                        ? ObjectLockMode.GOVERNANCE : ObjectLockMode.COMPLIANCE)
                .objectLockRetainUntilDate(retainUntil)
                .metadata(Map.of(
                        METADATA_CHAIN_HEAD, chainHead == null ? "" : chainHead,
                        METADATA_SIGNATURE_ALGORITHM, contentSigner.algorithm()))
                .build();
        client.putObject(request, RequestBody.fromBytes(body));
    }

    private static String segmentKey(S3ObjectLockSinkConfig config, List<AuditExportEvent> batch) {
        var first = batch.getFirst();
        var last = batch.getLast();
        return config.prefix()
                + DAY_PATH.format(first.createdAt())
                + "/audit-" + first.organizationId()
                + "-" + KEY_TIMESTAMP.format(first.createdAt())
                + "-" + KEY_TIMESTAMP.format(last.createdAt())
                + "-" + last.id()
                + ".jsonl";
    }
}
