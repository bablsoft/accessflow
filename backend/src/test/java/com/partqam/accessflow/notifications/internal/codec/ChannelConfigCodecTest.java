package com.partqam.accessflow.notifications.internal.codec;

import com.partqam.accessflow.core.api.CredentialEncryptionService;
import com.partqam.accessflow.notifications.api.NotificationChannelConfigException;
import com.partqam.accessflow.notifications.api.NotificationChannelType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelConfigCodecTest {

    private final CredentialEncryptionService encryption = new ReversibleEncryption();
    private final ChannelConfigCodec codec = new ChannelConfigCodec(JsonMapper.builder().build(),
            encryption);

    @Test
    void encodesEmailChannelAndMasksOnRead() {
        var json = codec.encodeForPersistence(NotificationChannelType.EMAIL, Map.of(
                "smtp_host", "smtp.example.com",
                "smtp_port", 587,
                "smtp_user", "u",
                "smtp_password", "secret-pw",
                "smtp_tls", true,
                "from_address", "from@example.com",
                "from_name", "AccessFlow"));

        // Persisted JSON stores the encrypted form, never the unsuffixed plaintext key.
        assertThat(json).contains("smtp_password_encrypted")
                .contains("enc:secret-pw")
                .doesNotContain("\"smtp_password\"");

        var view = codec.decodeForApi(json);
        assertThat(view).containsEntry("smtp_password", "********")
                .doesNotContainKey("smtp_password_encrypted");

        var typed = codec.decodeEmail(json);
        assertThat(typed.smtpHost()).isEqualTo("smtp.example.com");
        assertThat(typed.smtpPort()).isEqualTo(587);
        assertThat(typed.smtpPasswordPlain()).isEqualTo("secret-pw");
    }

    @Test
    void encodesWebhookChannelAndMasksSecret() {
        var json = codec.encodeForPersistence(NotificationChannelType.WEBHOOK, Map.of(
                "url", "https://hooks.example.com/x",
                "secret", "topsecret",
                "timeout_seconds", 5));

        var typed = codec.decodeWebhook(json);
        assertThat(typed.secretPlain()).isEqualTo("topsecret");
        assertThat(typed.timeoutSeconds()).isEqualTo(5);

        var view = codec.decodeForApi(json);
        assertThat(view).containsEntry("secret", "********");
    }

    @Test
    void encodesSlackChannel() {
        var json = codec.encodeForPersistence(NotificationChannelType.SLACK, Map.of(
                "webhook_url", "https://hooks.slack.com/services/abc",
                "channel", "#ops",
                "mention_users", java.util.List.of("@alice")));

        var typed = codec.decodeSlack(json);
        assertThat(typed.webhookUrl().toString()).isEqualTo("https://hooks.slack.com/services/abc");
        assertThat(typed.channel()).isEqualTo("#ops");
        assertThat(typed.mentionUsers()).containsExactly("@alice");
    }

    @Test
    void mergePreservesExistingCipherWhenMaskSent() {
        var original = codec.encodeForPersistence(NotificationChannelType.WEBHOOK, Map.of(
                "url", "https://h.example/x",
                "secret", "old-secret"));

        var merged = codec.mergeForPersistence(NotificationChannelType.WEBHOOK, original, Map.of(
                "secret", "********"));

        assertThat(codec.decodeWebhook(merged).secretPlain()).isEqualTo("old-secret");
    }

    @Test
    void mergeRotatesSecretWhenNewValueProvided() {
        var original = codec.encodeForPersistence(NotificationChannelType.WEBHOOK, Map.of(
                "url", "https://h.example/x",
                "secret", "old"));

        var merged = codec.mergeForPersistence(NotificationChannelType.WEBHOOK, original, Map.of(
                "secret", "new"));

        assertThat(codec.decodeWebhook(merged).secretPlain()).isEqualTo("new");
    }

    @Test
    void rejectsEmailWithoutPassword() {
        assertThatThrownBy(() -> codec.encodeForPersistence(NotificationChannelType.EMAIL, Map.of(
                "smtp_host", "smtp.example.com",
                "smtp_port", 587,
                "from_address", "x@example.com")))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("smtp_password");
    }

    @Test
    void rejectsWebhookWithoutSecret() {
        assertThatThrownBy(() -> codec.encodeForPersistence(NotificationChannelType.WEBHOOK, Map.of(
                "url", "https://h.example/x")))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("secret");
    }

    @Test
    void rejectsSlackWithoutWebhookUrl() {
        assertThatThrownBy(() -> codec.encodeForPersistence(NotificationChannelType.SLACK, Map.of(
                "channel", "#ops")))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("webhook_url");
    }

    @Test
    void rejectsSlackWithBlankWebhookUrl() {
        var input = new java.util.HashMap<String, Object>();
        input.put("webhook_url", " ");
        assertThatThrownBy(() -> codec.encodeForPersistence(NotificationChannelType.SLACK, input))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("webhook_url");
    }

    @Test
    void rejectsSlackWithMalformedWebhookUrl() {
        var input = new java.util.HashMap<String, Object>();
        input.put("webhook_url", "not a uri with spaces");
        assertThatThrownBy(() -> codec.encodeForPersistence(NotificationChannelType.SLACK, input))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("valid URI");
    }

    @Test
    void rejectsEmailWithNonNumericPort() {
        var input = new java.util.HashMap<String, Object>();
        input.put("smtp_host", "smtp.example.com");
        input.put("smtp_port", "not-a-number");
        input.put("smtp_password", "pw");
        input.put("from_address", "from@example.com");
        assertThatThrownBy(() -> codec.encodeForPersistence(NotificationChannelType.EMAIL, input))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("smtp_port");
    }

    @Test
    void emailPortAcceptsStringNumeric() {
        var json = codec.encodeForPersistence(NotificationChannelType.EMAIL, Map.of(
                "smtp_host", "smtp.example.com",
                "smtp_port", "587",
                "smtp_password", "pw",
                "from_address", "from@example.com"));
        var decoded = codec.decodeEmail(json);
        assertThat(decoded.smtpPort()).isEqualTo(587);
    }

    @Test
    void emailMissingPortRejected() {
        var input = new java.util.HashMap<String, Object>();
        input.put("smtp_host", "smtp.example.com");
        input.put("smtp_password", "pw");
        input.put("from_address", "from@example.com");
        assertThatThrownBy(() -> codec.encodeForPersistence(NotificationChannelType.EMAIL, input))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("smtp_port");
    }

    @Test
    void mergeWithNullPartialReturnsExistingJson() {
        var original = codec.encodeForPersistence(NotificationChannelType.SLACK, Map.of(
                "webhook_url", "https://hooks.slack.com/x"));
        var merged = codec.mergeForPersistence(NotificationChannelType.SLACK, original, null);
        assertThat(merged).isEqualTo(original);
    }

    @Test
    void decodeForApiHandlesEmptyJson() {
        var view = codec.decodeForApi("");
        assertThat(view).isEmpty();
    }

    @Test
    void decodeForApiHandlesNullJson() {
        var view = codec.decodeForApi(null);
        assertThat(view).isEmpty();
    }

    @Test
    void decodeForApiHandlesInvalidJson() {
        assertThatThrownBy(() -> codec.decodeForApi("{not-json"))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void slackMentionUsersAcceptsScalarAndList() {
        var withList = codec.encodeForPersistence(NotificationChannelType.SLACK, Map.of(
                "webhook_url", "https://hooks.slack.com/x",
                "mention_users", java.util.List.of("@alice", "@bob")));
        assertThat(codec.decodeSlack(withList).mentionUsers()).containsExactly("@alice", "@bob");

        var withScalar = codec.encodeForPersistence(NotificationChannelType.SLACK, Map.of(
                "webhook_url", "https://hooks.slack.com/x",
                "mention_users", "@solo"));
        assertThat(codec.decodeSlack(withScalar).mentionUsers()).containsExactly("@solo");

        var without = codec.encodeForPersistence(NotificationChannelType.SLACK, Map.of(
                "webhook_url", "https://hooks.slack.com/x"));
        assertThat(codec.decodeSlack(without).mentionUsers()).isEmpty();
    }

    @Test
    void webhookTimeoutDefaultsTo10WhenAbsent() {
        var json = codec.encodeForPersistence(NotificationChannelType.WEBHOOK, Map.of(
                "url", "https://h.example/x",
                "secret", "topsecret"));
        assertThat(codec.decodeWebhook(json).timeoutSeconds()).isEqualTo(10);
    }

    @Test
    void webhookTimeoutFallsBackToDefaultOnMalformedValue() {
        // Build manually to avoid the encoder rewriting the value.
        var raw = "{\"url\":\"https://h.example/x\",\"secret_encrypted\":\"enc:x\","
                + "\"timeout_seconds\":\"not-a-number\"}";
        assertThat(codec.decodeWebhook(raw).timeoutSeconds()).isEqualTo(10);
    }

    @Test
    void emailDecodeAcceptsMissingTlsAsTrue() {
        var json = codec.encodeForPersistence(NotificationChannelType.EMAIL, Map.of(
                "smtp_host", "smtp.example.com",
                "smtp_port", 587,
                "smtp_password", "pw",
                "from_address", "from@example.com"));
        assertThat(codec.decodeEmail(json).smtpTls()).isTrue();
    }

    @Test
    void mergePreservesExistingSecretWhenPartialOmitsKey() {
        var original = codec.encodeForPersistence(NotificationChannelType.WEBHOOK, Map.of(
                "url", "https://h.example/x",
                "secret", "kept"));
        var merged = codec.mergeForPersistence(NotificationChannelType.WEBHOOK, original, Map.of(
                "url", "https://h.example/y"));
        assertThat(codec.decodeWebhook(merged).secretPlain()).isEqualTo("kept");
    }

    /**
     * Trivial reversible "encryption" used to exercise the round-trip without depending on
     * core's package-private AES helper. Prepends a marker so encrypted values are visibly
     * distinct from plaintext in test assertions.
     */
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
