package com.bablsoft.accessflow.audit.internal.sink;

import com.bablsoft.accessflow.audit.api.AuditSinkType;
import com.bablsoft.accessflow.audit.internal.codec.AuditSinkConfigCodec;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditSinkEntity;
import com.bablsoft.accessflow.core.api.ContentSigner;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.localstack.LocalStackContainer;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live-path proof for the WORM sink (#628) against a real S3 API (LocalStack): the segment and
 * its {@code .sig} sidecar land under an Object Lock retention, and the detached signature
 * verifies offline with plain {@code java.security.Signature} — the same SHA256withRSA contract
 * as {@code ExportSignatureService}.
 */
class S3ObjectLockSinkDelivererIntegrationTest {

    // Pin to the community-edition semver line — the date-tagged images (2026.x) are the
    // licensed Pro build and exit at startup without an auth token.
    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer("localstack/localstack:4.9.2").withServices("s3");

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
    private static final String BUCKET = "audit-worm";
    private static final UUID ORG_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private static KeyPair keyPair;
    private static AuditSinkConfigCodec codec;
    private static AuditExportEventWriter eventWriter;
    private static S3ClientFactory clientFactory;
    private static S3ObjectLockSinkDeliverer deliverer;
    private static AuditSinkEntity sink;

    @BeforeAll
    static void startLocalStack() throws Exception {
        LOCALSTACK.start();
        keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        var mapper = JsonMapper.builder().build();
        codec = new AuditSinkConfigCodec(mapper, new ReversibleEncryption());
        eventWriter = new AuditExportEventWriter(mapper);
        clientFactory = new S3ClientFactory();
        deliverer = new S3ObjectLockSinkDeliverer(codec, eventWriter, clientFactory,
                new RsaContentSigner(), Clock.fixed(NOW, ZoneOffset.UTC));

        sink = new AuditSinkEntity();
        sink.setId(UUID.randomUUID());
        sink.setOrganizationId(ORG_ID);
        sink.setName("worm");
        sink.setType(AuditSinkType.S3_OBJECT_LOCK);
        sink.setConfigJson(codec.encodeForPersistence(AuditSinkType.S3_OBJECT_LOCK, Map.of(
                "bucket", BUCKET,
                "region", LOCALSTACK.getRegion(),
                "access_key_id", LOCALSTACK.getAccessKey(),
                "secret_access_key", LOCALSTACK.getSecretKey(),
                "endpoint", LOCALSTACK.getEndpoint().toString(),
                "retention_days", 30)));

        try (var client = clientFactory.create(codec.decodeS3ObjectLock(sink.getConfigJson()))) {
            client.createBucket(CreateBucketRequest.builder()
                    .bucket(BUCKET)
                    .objectLockEnabledForBucket(true)
                    .build());
        }
    }

    @AfterAll
    static void stopLocalStack() {
        LOCALSTACK.stop();
    }

    private static AuditExportEvent event(UUID id, Instant createdAt, String currentHash) {
        return new AuditExportEvent(id, ORG_ID, UUID.randomUUID(), "QUERY_SUBMITTED",
                "query_request", UUID.randomUUID(), "{\"seq\":1}", "203.0.113.5", "ua/1",
                createdAt, "aa11", currentHash);
    }

    @Test
    void uploadsLockedSegmentWithVerifiableDetachedSignature() throws Exception {
        var lastId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        var batch = List.of(
                event(UUID.randomUUID(), Instant.parse("2026-08-19T10:15:30.123456Z"), "cafe01"),
                event(lastId, Instant.parse("2026-08-19T10:16:31.654321Z"), "cafe02"));

        deliverer.deliver(sink, batch);

        var expectedKey = "audit/2026/08/19/audit-" + ORG_ID
                + "-20260819T101530.123456-20260819T101631.654321-" + lastId + ".jsonl";
        try (var client = clientFactory.create(codec.decodeS3ObjectLock(sink.getConfigJson()))) {
            var segment = client.getObject(GetObjectRequest.builder()
                    .bucket(BUCKET).key(expectedKey).build());
            var segmentBytes = segment.readAllBytes();
            assertThat(new String(segmentBytes, StandardCharsets.UTF_8))
                    .isEqualTo(eventWriter.toJsonLines(batch));
            assertThat(segment.response().objectLockMode()).isEqualTo(ObjectLockMode.COMPLIANCE);
            assertThat(segment.response().objectLockRetainUntilDate())
                    .isEqualTo(NOW.plus(Duration.ofDays(30)));
            assertThat(segment.response().metadata())
                    .containsEntry("chain-head", "cafe02")
                    .containsEntry("signature-algorithm", "SHA256withRSA");

            var sig = client.getObject(GetObjectRequest.builder()
                    .bucket(BUCKET).key(expectedKey + ".sig").build());
            var signatureBase64 = new String(sig.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sig.response().objectLockMode()).isEqualTo(ObjectLockMode.COMPLIANCE);

            var verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(keyPair.getPublic());
            verifier.update(segmentBytes);
            assertThat(verifier.verify(Base64.getDecoder().decode(signatureBase64))).isTrue();
        }
    }

    @Test
    void deliverTestUploadsUnlockedProbeObject() throws Exception {
        deliverer.deliverTest(sink, event(UUID.randomUUID(), NOW, "aa"));

        try (var client = clientFactory.create(codec.decodeS3ObjectLock(sink.getConfigJson()))) {
            var listed = client.listObjectsV2(b -> b.bucket(BUCKET).prefix("audit/test/"));
            assertThat(listed.contents()).hasSize(1);
            var probe = client.getObject(GetObjectRequest.builder()
                    .bucket(BUCKET).key(listed.contents().get(0).key()).build());
            probe.readAllBytes();
            assertThat(probe.response().objectLockMode()).isNull();
            assertThat(probe.response().objectLockRetainUntilDate()).isNull();
        }
    }

    /** SHA256withRSA over the deployment key — what ExportSignatureService does in production. */
    private static final class RsaContentSigner implements ContentSigner {
        @Override
        public String sign(byte[] content) {
            try {
                var signature = Signature.getInstance("SHA256withRSA");
                signature.initSign(keyPair.getPrivate());
                signature.update(content);
                return Base64.getEncoder().encodeToString(signature.sign());
            } catch (java.security.GeneralSecurityException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public String algorithm() {
            return "SHA256withRSA";
        }

        @Override
        public String publicKeyPem() {
            return "";
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
