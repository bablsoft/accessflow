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
