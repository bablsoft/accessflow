package com.bablsoft.accessflow.audit.internal.codec;

import com.bablsoft.accessflow.audit.api.AuditSinkConfigException;
import com.bablsoft.accessflow.audit.api.AuditSinkType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditSinkConfigCodecTest {

    private final CredentialEncryptionService encryption = new ReversibleEncryption();
    private final AuditSinkConfigCodec codec =
            new AuditSinkConfigCodec(JsonMapper.builder().build(), encryption);

    // ---------------------------------------------------------------- SPLUNK_HEC

    @Test
    void encodesSplunkHecAndMasksTokenOnRead() {
        var json = codec.encodeForPersistence(AuditSinkType.SPLUNK_HEC, Map.of(
                "url", "https://splunk.example.com:8088/services/collector",
                "token", "hec-token",
                "index", "audit",
                "source", "af"));

        assertThat(json).contains("token_encrypted")
                .contains("enc:hec-token")
                .doesNotContain("\"token\"");

        var view = codec.decodeForApi(json);
        assertThat(view).containsEntry("token", "********")
                .doesNotContainKey("token_encrypted");

        var typed = codec.decodeSplunkHec(json);
        assertThat(typed.url().toString())
                .isEqualTo("https://splunk.example.com:8088/services/collector");
        assertThat(typed.tokenPlain()).isEqualTo("hec-token");
        assertThat(typed.index()).isEqualTo("audit");
        assertThat(typed.source()).isEqualTo("af");
    }

    @Test
    void splunkHecSourceDefaultsWhenAbsent() {
        var json = codec.encodeForPersistence(AuditSinkType.SPLUNK_HEC, Map.of(
                "url", "https://splunk.example.com/hec",
                "token", "t"));

        var typed = codec.decodeSplunkHec(json);
        assertThat(typed.source()).isEqualTo(SplunkHecSinkConfig.DEFAULT_SOURCE);
        assertThat(typed.index()).isNull();
    }

    @Test
    void rejectsSplunkHecWithoutUrl() {
        assertThatThrownBy(() -> codec.encodeForPersistence(AuditSinkType.SPLUNK_HEC, Map.of(
                "token", "t")))
                .isInstanceOf(AuditSinkConfigException.class)
                .hasMessageContaining("url");
    }

    @Test
    void rejectsSplunkHecWithoutToken() {
        assertThatThrownBy(() -> codec.encodeForPersistence(AuditSinkType.SPLUNK_HEC, Map.of(
                "url", "https://splunk.example.com/hec")))
                .isInstanceOf(AuditSinkConfigException.class)
                .hasMessageContaining("token");
    }

    @Test
    void rejectsSplunkHecWithMalformedUrl() {
        assertThatThrownBy(() -> codec.encodeForPersistence(AuditSinkType.SPLUNK_HEC, Map.of(
                "url", "not a uri with spaces",
                "token", "t")))
                .isInstanceOf(AuditSinkConfigException.class)
                .hasMessageContaining("valid URI");
    }

    // ---------------------------------------------------------------- SYSLOG_CEF

    @Test
    void encodesSyslogCefTcp() {
        var json = codec.encodeForPersistence(AuditSinkType.SYSLOG_CEF, Map.of(
                "host", "siem.example.com",
                "port", 6514,
                "protocol", "TCP"));

        var typed = codec.decodeSyslogCef(json);
        assertThat(typed.host()).isEqualTo("siem.example.com");
        assertThat(typed.port()).isEqualTo(6514);
        assertThat(typed.tls()).isFalse();
    }

    @Test
    void syslogCefTlsProtocolIsCaseInsensitive() {
        var json = codec.encodeForPersistence(AuditSinkType.SYSLOG_CEF, Map.of(
                "host", "siem.example.com",
                "port", "6514",
                "protocol", "tls"));

        assertThat(codec.decodeSyslogCef(json).tls()).isTrue();
    }

    @Test
    void rejectsSyslogCefWithoutHost() {
        assertThatThrownBy(() -> codec.encodeForPersistence(AuditSinkType.SYSLOG_CEF, Map.of(
                "port", 6514,
                "protocol", "TCP")))
                .isInstanceOf(AuditSinkConfigException.class)
                .hasMessageContaining("host");
    }

    @Test
    void rejectsSyslogCefWithoutPort() {
        assertThatThrownBy(() -> codec.encodeForPersistence(AuditSinkType.SYSLOG_CEF, Map.of(
                "host", "siem.example.com",
                "protocol", "TCP")))
                .isInstanceOf(AuditSinkConfigException.class)
                .hasMessageContaining("port");
    }

    @Test
    void rejectsSyslogCefWithBadProtocol() {
        assertThatThrownBy(() -> codec.encodeForPersistence(AuditSinkType.SYSLOG_CEF, Map.of(
                "host", "siem.example.com",
                "port", 6514,
                "protocol", "UDP")))
                .isInstanceOf(AuditSinkConfigException.class)
                .hasMessageContaining("TCP or TLS");
    }

    @Test
    void rejectsSyslogCefPortOutOfBounds() {
        assertThatThrownBy(() -> codec.encodeForPersistence(AuditSinkType.SYSLOG_CEF, Map.of(
                "host", "siem.example.com",
                "port", 0,
                "protocol", "TCP")))
                .isInstanceOf(AuditSinkConfigException.class)
                .hasMessageContaining("between 1 and 65535");
        assertThatThrownBy(() -> codec.encodeForPersistence(AuditSinkType.SYSLOG_CEF, Map.of(
                "host", "siem.example.com",
                "port", 65536,
                "protocol", "TCP")))
                .isInstanceOf(AuditSinkConfigException.class)
                .hasMessageContaining("between 1 and 65535");
    }

    @Test
    void rejectsSyslogCefNonNumericPort() {
        assertThatThrownBy(() -> codec.encodeForPersistence(AuditSinkType.SYSLOG_CEF, Map.of(
                "host", "siem.example.com",
                "port", "not-a-number",
                "protocol", "TCP")))
                .isInstanceOf(AuditSinkConfigException.class)
                .hasMessageContaining("numeric");
    }

    // ---------------------------------------------------------------- HTTPS_BATCH

    @Test
    void encodesHttpsBatchAndMasksSecret() {
        var json = codec.encodeForPersistence(AuditSinkType.HTTPS_BATCH, Map.of(
                "url", "https://receiver.example.com/audit",
                "secret", "topsecret"));

        assertThat(json).contains("secret_encrypted")
                .contains("enc:topsecret")
                .doesNotContain("\"secret\"");

        var view = codec.decodeForApi(json);
        assertThat(view).containsEntry("secret", "********")
                .doesNotContainKey("secret_encrypted");

        var typed = codec.decodeHttpsBatch(json);
        assertThat(typed.url().toString()).isEqualTo("https://receiver.example.com/audit");
        assertThat(typed.secretPlain()).isEqualTo("topsecret");
    }

    @Test
    void rejectsHttpsBatchWithoutUrl() {
        assertThatThrownBy(() -> codec.encodeForPersistence(AuditSinkType.HTTPS_BATCH, Map.of(
                "secret", "s")))
                .isInstanceOf(AuditSinkConfigException.class)
                .hasMessageContaining("url");
    }

    @Test
    void rejectsHttpsBatchWithoutSecret() {
        assertThatThrownBy(() -> codec.encodeForPersistence(AuditSinkType.HTTPS_BATCH, Map.of(
                "url", "https://receiver.example.com/audit")))
                .isInstanceOf(AuditSinkConfigException.class)
                .hasMessageContaining("secret");
    }

    // ---------------------------------------------------------------- S3_OBJECT_LOCK

    @Test
    void encodesS3ObjectLockAndAppliesDefaults() {
        var json = codec.encodeForPersistence(AuditSinkType.S3_OBJECT_LOCK, Map.of(
                "bucket", "audit-worm",
                "region", "eu-west-1",
                "access_key_id", "AKIA123",
                "secret_access_key", "s3-secret",
                "retention_days", 365));

        assertThat(json).contains("secret_access_key_encrypted")
                .contains("enc:s3-secret")
                .doesNotContain("\"secret_access_key\"");

        var view = codec.decodeForApi(json);
        assertThat(view).containsEntry("secret_access_key", "********")
                .doesNotContainKey("secret_access_key_encrypted");

        var typed = codec.decodeS3ObjectLock(json);
        assertThat(typed.bucket()).isEqualTo("audit-worm");
        assertThat(typed.region()).isEqualTo("eu-west-1");
        assertThat(typed.prefix()).isEqualTo(S3ObjectLockSinkConfig.DEFAULT_PREFIX);
        assertThat(typed.accessKeyId()).isEqualTo("AKIA123");
        assertThat(typed.secretAccessKeyPlain()).isEqualTo("s3-secret");
        assertThat(typed.endpoint()).isNull();
        assertThat(typed.retentionMode()).isEqualTo(S3ObjectLockSinkConfig.RetentionMode.COMPLIANCE);
        assertThat(typed.retentionDays()).isEqualTo(365);
        assertThat(typed.segmentMaxAge()).isEqualTo(S3ObjectLockSinkConfig.DEFAULT_SEGMENT_MAX_AGE);
    }

    @Test
    void decodesS3ObjectLockExplicitValues() {
        var json = codec.encodeForPersistence(AuditSinkType.S3_OBJECT_LOCK, Map.of(
                "bucket", "audit-worm",
                "region", "eu-west-1",
                "prefix", "worm/",
                "access_key_id", "AKIA123",
                "secret_access_key", "s3-secret",
                "endpoint", "http://localhost:9000",
                "retention_mode", "governance",
                "retention_days", "30",
                "segment_max_age", "PT5M"));

        var typed = codec.decodeS3ObjectLock(json);
        assertThat(typed.prefix()).isEqualTo("worm/");
        assertThat(typed.endpoint()).isEqualTo("http://localhost:9000");
        assertThat(typed.retentionMode()).isEqualTo(S3ObjectLockSinkConfig.RetentionMode.GOVERNANCE);
        assertThat(typed.retentionDays()).isEqualTo(30);
        assertThat(typed.segmentMaxAge()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void normalizesS3PrefixToTrailingSlash() {
        var json = codec.encodeForPersistence(AuditSinkType.S3_OBJECT_LOCK, Map.of(
                "bucket", "audit-worm",
                "region", "eu-west-1",
                "prefix", "archive",
                "access_key_id", "AKIA123",
                "secret_access_key", "s3-secret",
                "retention_days", 30));

        assertThat(codec.decodeS3ObjectLock(json).prefix()).isEqualTo("archive/");
    }

    @Test
    void rejectsClientSuppliedCiphertextKeys() {
        // A raw *_encrypted key from the client must not satisfy the secret requirement: it
        // would pass validation and then fail decryption on every drain tick.
        assertThatThrownBy(() -> codec.encodeForPersistence(AuditSinkType.SPLUNK_HEC, Map.of(
                "url", "https://splunk.example.com:8088/services/collector/event",
                "token_encrypted", "attacker-chosen-ciphertext")))
                .isInstanceOf(AuditSinkConfigException.class)
                .hasMessageContaining("token");
    }

    @Test
    void rejectsS3ObjectLockMissingRequiredKeys() {
        var complete = new HashMap<String, Object>(Map.of(
                "bucket", "b",
                "region", "r",
                "access_key_id", "a",
                "secret_access_key", "s",
                "retention_days", 1));
        for (var missing : new String[]{
                "bucket", "region", "access_key_id", "secret_access_key", "retention_days"}) {
            var input = new HashMap<String, Object>(complete);
            input.remove(missing);
            assertThatThrownBy(() -> codec.encodeForPersistence(AuditSinkType.S3_OBJECT_LOCK, input))
                    .as("missing " + missing)
                    .isInstanceOf(AuditSinkConfigException.class)
                    .hasMessageContaining(missing);
        }
    }

    @Test
    void rejectsS3ObjectLockInvalidRetentionMode() {
        assertThatThrownBy(() -> codec.encodeForPersistence(AuditSinkType.S3_OBJECT_LOCK, Map.of(
                "bucket", "b",
                "region", "r",
                "access_key_id", "a",
                "secret_access_key", "s",
                "retention_days", 1,
                "retention_mode", "FOREVER")))
                .isInstanceOf(AuditSinkConfigException.class)
                .hasMessageContaining("COMPLIANCE or GOVERNANCE");
    }

    @Test
    void rejectsS3ObjectLockRetentionDaysBelowOne() {
        assertThatThrownBy(() -> codec.encodeForPersistence(AuditSinkType.S3_OBJECT_LOCK, Map.of(
                "bucket", "b",
                "region", "r",
                "access_key_id", "a",
                "secret_access_key", "s",
                "retention_days", 0)))
                .isInstanceOf(AuditSinkConfigException.class)
                .hasMessageContaining("at least 1");
    }

    @Test
    void rejectsS3ObjectLockUnparseableSegmentMaxAge() {
        assertThatThrownBy(() -> codec.encodeForPersistence(AuditSinkType.S3_OBJECT_LOCK, Map.of(
                "bucket", "b",
                "region", "r",
                "access_key_id", "a",
                "secret_access_key", "s",
                "retention_days", 1,
                "segment_max_age", "15 minutes")))
                .isInstanceOf(AuditSinkConfigException.class)
                .hasMessageContaining("ISO-8601");
    }

    @Test
    void rejectsS3ObjectLockNonPositiveSegmentMaxAge() {
        assertThatThrownBy(() -> codec.encodeForPersistence(AuditSinkType.S3_OBJECT_LOCK, Map.of(
                "bucket", "b",
                "region", "r",
                "access_key_id", "a",
                "secret_access_key", "s",
                "retention_days", 1,
                "segment_max_age", "PT0S")))
                .isInstanceOf(AuditSinkConfigException.class)
                .hasMessageContaining("positive");
    }

    // ---------------------------------------------------------------- merge

    @Test
    void mergePreservesExistingCipherWhenMaskSent() {
        var original = codec.encodeForPersistence(AuditSinkType.HTTPS_BATCH, Map.of(
                "url", "https://receiver.example.com/audit",
                "secret", "old-secret"));

        var merged = codec.mergeForPersistence(AuditSinkType.HTTPS_BATCH, original, Map.of(
                "url", "https://receiver.example.com/v2",
                "secret", "********"));

        var typed = codec.decodeHttpsBatch(merged);
        assertThat(typed.url().toString()).isEqualTo("https://receiver.example.com/v2");
        assertThat(typed.secretPlain()).isEqualTo("old-secret");
    }

    @Test
    void mergeRotatesSecretWhenNewValueProvided() {
        var original = codec.encodeForPersistence(AuditSinkType.SPLUNK_HEC, Map.of(
                "url", "https://splunk.example.com/hec",
                "token", "old"));

        var merged = codec.mergeForPersistence(AuditSinkType.SPLUNK_HEC, original, Map.of(
                "token", "rotated"));

        assertThat(codec.decodeSplunkHec(merged).tokenPlain()).isEqualTo("rotated");
    }

    @Test
    void mergeWithNullPartialReturnsExistingJson() {
        var original = codec.encodeForPersistence(AuditSinkType.HTTPS_BATCH, Map.of(
                "url", "https://receiver.example.com/audit",
                "secret", "s"));

        assertThat(codec.mergeForPersistence(AuditSinkType.HTTPS_BATCH, original, null))
                .isEqualTo(original);
    }

    @Test
    void mergeStillValidatesTheResult() {
        var original = codec.encodeForPersistence(AuditSinkType.SYSLOG_CEF, Map.of(
                "host", "siem.example.com",
                "port", 6514,
                "protocol", "TCP"));

        assertThatThrownBy(() -> codec.mergeForPersistence(AuditSinkType.SYSLOG_CEF, original,
                Map.of("port", 99999)))
                .isInstanceOf(AuditSinkConfigException.class)
                .hasMessageContaining("between 1 and 65535");
    }

    @Test
    void rejectsUnparseableStoredJson() {
        assertThatThrownBy(() -> codec.decodeForApi("not-json{"))
                .isInstanceOf(AuditSinkConfigException.class)
                .hasMessageContaining("valid JSON");
    }

    /** Trivial reversible "encryption" so round-trips are assertable without core's AES helper. */
    private static final class ReversibleEncryption implements CredentialEncryptionService {

        private static final String PREFIX = "enc:";

        @Override
        public String encrypt(String plaintext) {
            return PREFIX + plaintext;
        }

        @Override
        public String decrypt(String ciphertext) {
            if (ciphertext == null || !ciphertext.startsWith(PREFIX)) {
                throw new IllegalStateException("not a ciphertext: " + ciphertext);
            }
            return ciphertext.substring(PREFIX.length());
        }
    }
}
