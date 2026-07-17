package com.bablsoft.accessflow.notifications.internal.codec;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.notifications.api.NotificationChannelConfigException;
import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
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

    @Test
    void encodesDiscordChannel() {
        var json = codec.encodeForPersistence(NotificationChannelType.DISCORD, Map.of(
                "webhook_url", "https://discord.com/api/webhooks/123/abc",
                "username", "AccessFlow",
                "avatar_url", "https://accessflow.example/logo.png"));

        var typed = codec.decodeDiscord(json);
        assertThat(typed.webhookUrl().toString())
                .isEqualTo("https://discord.com/api/webhooks/123/abc");
        assertThat(typed.username()).isEqualTo("AccessFlow");
        assertThat(typed.avatarUrl()).isEqualTo("https://accessflow.example/logo.png");

        var view = codec.decodeForApi(json);
        assertThat(view).containsEntry("webhook_url", "https://discord.com/api/webhooks/123/abc");
    }

    @Test
    void rejectsDiscordWithoutWebhookUrl() {
        assertThatThrownBy(() -> codec.encodeForPersistence(NotificationChannelType.DISCORD, Map.of(
                "username", "bot")))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("webhook_url");
    }

    @Test
    void encodesTelegramChannelAndMasksBotToken() {
        var json = codec.encodeForPersistence(NotificationChannelType.TELEGRAM, Map.of(
                "bot_token", "123:abc",
                "chat_id", "-100123"));

        assertThat(json).contains("bot_token_encrypted")
                .contains("enc:123:abc")
                .doesNotContain("\"bot_token\":\"123:abc\"");

        var typed = codec.decodeTelegram(json);
        assertThat(typed.botTokenPlain()).isEqualTo("123:abc");
        assertThat(typed.chatId()).isEqualTo("-100123");

        var view = codec.decodeForApi(json);
        assertThat(view).containsEntry("bot_token", "********")
                .doesNotContainKey("bot_token_encrypted")
                .containsEntry("chat_id", "-100123");
    }

    @Test
    void rejectsTelegramWithoutBotToken() {
        assertThatThrownBy(() -> codec.encodeForPersistence(NotificationChannelType.TELEGRAM, Map.of(
                "chat_id", "-100123")))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("bot_token");
    }

    @Test
    void rejectsTelegramWithoutChatId() {
        assertThatThrownBy(() -> codec.encodeForPersistence(NotificationChannelType.TELEGRAM, Map.of(
                "bot_token", "123:abc")))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("chat_id");
    }

    @Test
    void mergeRotatesTelegramBotTokenAndPreservesOnMask() {
        var original = codec.encodeForPersistence(NotificationChannelType.TELEGRAM, Map.of(
                "bot_token", "old",
                "chat_id", "-100"));

        var rotated = codec.mergeForPersistence(NotificationChannelType.TELEGRAM, original, Map.of(
                "bot_token", "new"));
        assertThat(codec.decodeTelegram(rotated).botTokenPlain()).isEqualTo("new");

        var kept = codec.mergeForPersistence(NotificationChannelType.TELEGRAM, original, Map.of(
                "bot_token", "********"));
        assertThat(codec.decodeTelegram(kept).botTokenPlain()).isEqualTo("old");
    }

    @Test
    void encodesMsTeamsChannel() {
        var json = codec.encodeForPersistence(NotificationChannelType.MS_TEAMS, Map.of(
                "webhook_url", "https://example.webhook.office.com/webhookb2/abc"));

        var typed = codec.decodeMsTeams(json);
        assertThat(typed.webhookUrl().toString())
                .isEqualTo("https://example.webhook.office.com/webhookb2/abc");
    }

    @Test
    void rejectsMsTeamsWithoutWebhookUrl() {
        assertThatThrownBy(() -> codec.encodeForPersistence(NotificationChannelType.MS_TEAMS, Map.of()))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("webhook_url");
    }

    @Test
    void encodesPagerDutyChannelAndMasksRoutingKey() {
        var json = codec.encodeForPersistence(NotificationChannelType.PAGERDUTY, Map.of(
                "routing_key", "R0UT1NGKEY",
                "default_severity", "critical",
                "triggers", java.util.List.of("CRITICAL_RISK", "REVIEW_TIMEOUT")));

        assertThat(json).contains("routing_key_encrypted")
                .contains("enc:R0UT1NGKEY")
                .doesNotContain("\"routing_key\":\"R0UT1NGKEY\"");

        var typed = codec.decodePagerDuty(json);
        assertThat(typed.routingKeyPlain()).isEqualTo("R0UT1NGKEY");
        assertThat(typed.defaultSeverity()).isEqualTo(PagerDutySeverity.CRITICAL);
        assertThat(typed.triggers())
                .containsExactlyInAnyOrder(PagerDutyTrigger.CRITICAL_RISK, PagerDutyTrigger.REVIEW_TIMEOUT);

        var view = codec.decodeForApi(json);
        assertThat(view).containsEntry("routing_key", "********")
                .doesNotContainKey("routing_key_encrypted")
                .containsEntry("default_severity", "critical");
    }

    @Test
    void rejectsPagerDutyWithoutRoutingKey() {
        assertThatThrownBy(() -> codec.encodeForPersistence(NotificationChannelType.PAGERDUTY, Map.of(
                "default_severity", "critical",
                "triggers", java.util.List.of("CRITICAL_RISK"))))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("routing_key");
    }

    @Test
    void rejectsPagerDutyWithInvalidSeverity() {
        assertThatThrownBy(() -> codec.encodeForPersistence(NotificationChannelType.PAGERDUTY, Map.of(
                "routing_key", "k",
                "default_severity", "fatal",
                "triggers", java.util.List.of("CRITICAL_RISK"))))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("default_severity");
    }

    @Test
    void rejectsPagerDutyWithoutTriggers() {
        assertThatThrownBy(() -> codec.encodeForPersistence(NotificationChannelType.PAGERDUTY, Map.of(
                "routing_key", "k",
                "default_severity", "warning",
                "triggers", java.util.List.of())))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("triggers");
    }

    @Test
    void rejectsPagerDutyWithInvalidTrigger() {
        assertThatThrownBy(() -> codec.encodeForPersistence(NotificationChannelType.PAGERDUTY, Map.of(
                "routing_key", "k",
                "default_severity", "info",
                "triggers", java.util.List.of("BOGUS"))))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("triggers");
    }

    @Test
    void mergeRotatesPagerDutyRoutingKeyAndPreservesOnMask() {
        var original = codec.encodeForPersistence(NotificationChannelType.PAGERDUTY, Map.of(
                "routing_key", "old",
                "default_severity", "error",
                "triggers", java.util.List.of("REVIEW_TIMEOUT")));

        var rotated = codec.mergeForPersistence(NotificationChannelType.PAGERDUTY, original, Map.of(
                "routing_key", "new"));
        assertThat(codec.decodePagerDuty(rotated).routingKeyPlain()).isEqualTo("new");

        var kept = codec.mergeForPersistence(NotificationChannelType.PAGERDUTY, original, Map.of(
                "routing_key", "********"));
        assertThat(codec.decodePagerDuty(kept).routingKeyPlain()).isEqualTo("old");
    }

    // --- ServiceNow (AF-453) -------------------------------------------------------------------

    private static Map<String, Object> serviceNowInput() {
        return new java.util.HashMap<>(Map.of(
                "instance_url", "https://dev1234.service-now.com",
                "username", "integration",
                "password", "sn-pw",
                "assignment_group", "DBA",
                "urgency", 2,
                "triggers", java.util.List.of("QUERY_REJECTED", "QUERY_ESCALATED"),
                "bidirectional_sync", true,
                "webhook_secret", "hook-secret"));
    }

    @Test
    void encodesServiceNowChannelEncryptsSecretsAndMasksOnRead() {
        var json = codec.encodeForPersistence(NotificationChannelType.SERVICENOW,
                serviceNowInput());

        assertThat(json).contains("password_encrypted")
                .contains("enc:sn-pw")
                .contains("webhook_secret_encrypted")
                .contains("enc:hook-secret")
                .doesNotContain("\"password\"")
                .doesNotContain("\"webhook_secret\"");

        var view = codec.decodeForApi(json);
        assertThat(view).containsEntry("password", "********")
                .containsEntry("webhook_secret", "********")
                .doesNotContainKey("password_encrypted")
                .doesNotContainKey("webhook_secret_encrypted");

        var typed = codec.decodeServiceNow(json);
        assertThat(typed.instanceUrl().toString()).isEqualTo("https://dev1234.service-now.com");
        assertThat(typed.username()).isEqualTo("integration");
        assertThat(typed.passwordPlain()).isEqualTo("sn-pw");
        assertThat(typed.assignmentGroup()).isEqualTo("DBA");
        assertThat(typed.urgency()).isEqualTo(2);
        assertThat(typed.triggers()).containsExactlyInAnyOrder(
                TicketingTrigger.QUERY_REJECTED, TicketingTrigger.QUERY_ESCALATED);
        assertThat(typed.bidirectionalSync()).isTrue();
        assertThat(typed.webhookSecretPlain()).isEqualTo("hook-secret");
        assertThat(typed.approveStatuses())
                .isEqualTo(TicketingChannelConfig.DEFAULT_APPROVE_STATUSES);
        assertThat(typed.rejectStatuses())
                .isEqualTo(TicketingChannelConfig.DEFAULT_REJECT_STATUSES);
    }

    @Test
    void serviceNowMinimalConfigAppliesDefaults() {
        var json = codec.encodeForPersistence(NotificationChannelType.SERVICENOW, Map.of(
                "instance_url", "https://dev1234.service-now.com",
                "username", "integration",
                "password", "sn-pw",
                "triggers", java.util.List.of("REVIEW_TIMEOUT")));

        var typed = codec.decodeServiceNow(json);
        assertThat(typed.assignmentGroup()).isNull();
        assertThat(typed.urgency()).isNull();
        assertThat(typed.bidirectionalSync()).isFalse();
        assertThat(typed.webhookSecretPlain()).isNull();
        assertThat(typed.approveStatuses())
                .isEqualTo(TicketingChannelConfig.DEFAULT_APPROVE_STATUSES);
        assertThat(typed.rejectStatuses())
                .isEqualTo(TicketingChannelConfig.DEFAULT_REJECT_STATUSES);
    }

    @Test
    void serviceNowCustomStatusListsOverrideDefaults() {
        var input = serviceNowInput();
        input.put("approve_statuses", java.util.List.of("Fixed"));
        input.put("reject_statuses", java.util.List.of("Nope", "Never"));
        var json = codec.encodeForPersistence(NotificationChannelType.SERVICENOW, input);

        var typed = codec.decodeServiceNow(json);
        assertThat(typed.approveStatuses()).containsExactly("Fixed");
        assertThat(typed.rejectStatuses()).containsExactly("Nope", "Never");
    }

    @Test
    void rejectsServiceNowWithoutRequiredKeys() {
        for (var missing : new String[]{"instance_url", "username", "password"}) {
            var input = serviceNowInput();
            input.remove(missing);
            assertThatThrownBy(() -> codec.encodeForPersistence(
                    NotificationChannelType.SERVICENOW, input))
                    .as("missing " + missing)
                    .isInstanceOf(NotificationChannelConfigException.class)
                    .hasMessageContaining(missing);
        }
    }

    @Test
    void rejectsServiceNowWithMalformedInstanceUrl() {
        var input = serviceNowInput();
        input.put("instance_url", "not a uri with spaces");
        assertThatThrownBy(() -> codec.encodeForPersistence(
                NotificationChannelType.SERVICENOW, input))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("valid URI");
    }

    @Test
    void rejectsServiceNowUrgencyOutOfRangeOrNonNumeric() {
        for (var bad : new Object[]{0, 4, "not-a-number"}) {
            var input = serviceNowInput();
            input.put("urgency", bad);
            assertThatThrownBy(() -> codec.encodeForPersistence(
                    NotificationChannelType.SERVICENOW, input))
                    .as("urgency " + bad)
                    .isInstanceOf(NotificationChannelConfigException.class)
                    .hasMessageContaining("urgency");
        }
    }

    @Test
    void serviceNowUrgencyBoundsAreAccepted() {
        for (var ok : new Object[]{1, 3, "2"}) {
            var input = serviceNowInput();
            input.put("urgency", ok);
            var json = codec.encodeForPersistence(NotificationChannelType.SERVICENOW, input);
            assertThat(codec.decodeServiceNow(json).urgency())
                    .isEqualTo(Integer.parseInt(ok.toString()));
        }
    }

    @Test
    void rejectsServiceNowWithoutTriggersOrWithInvalidTrigger() {
        var noTriggers = serviceNowInput();
        noTriggers.remove("triggers");
        assertThatThrownBy(() -> codec.encodeForPersistence(
                NotificationChannelType.SERVICENOW, noTriggers))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("triggers");

        var badTrigger = serviceNowInput();
        badTrigger.put("triggers", java.util.List.of("BOGUS"));
        assertThatThrownBy(() -> codec.encodeForPersistence(
                NotificationChannelType.SERVICENOW, badTrigger))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("triggers");
    }

    @Test
    void rejectsServiceNowBidirectionalSyncWithoutWebhookSecret() {
        var input = serviceNowInput();
        input.remove("webhook_secret");
        assertThatThrownBy(() -> codec.encodeForPersistence(
                NotificationChannelType.SERVICENOW, input))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("webhook_secret");
    }

    @Test
    void serviceNowWebhookSecretOptionalWhenSyncDisabled() {
        var input = serviceNowInput();
        input.remove("webhook_secret");
        input.put("bidirectional_sync", false);
        var json = codec.encodeForPersistence(NotificationChannelType.SERVICENOW, input);
        assertThat(codec.decodeServiceNow(json).webhookSecretPlain()).isNull();
    }

    @Test
    void mergePreservesServiceNowSecretsOnMaskAndRotatesOnNewValue() {
        var original = codec.encodeForPersistence(NotificationChannelType.SERVICENOW,
                serviceNowInput());

        var kept = codec.mergeForPersistence(NotificationChannelType.SERVICENOW, original, Map.of(
                "password", "********",
                "webhook_secret", "********"));
        assertThat(codec.decodeServiceNow(kept).passwordPlain()).isEqualTo("sn-pw");
        assertThat(codec.decodeServiceNow(kept).webhookSecretPlain()).isEqualTo("hook-secret");

        var rotated = codec.mergeForPersistence(NotificationChannelType.SERVICENOW, original,
                Map.of("password", "new-pw", "webhook_secret", "new-secret"));
        assertThat(codec.decodeServiceNow(rotated).passwordPlain()).isEqualTo("new-pw");
        assertThat(codec.decodeServiceNow(rotated).webhookSecretPlain()).isEqualTo("new-secret");
    }

    // --- Jira (AF-453) -------------------------------------------------------------------------

    private static Map<String, Object> jiraInput() {
        return new java.util.HashMap<>(Map.of(
                "base_url", "https://example.atlassian.net",
                "user_email", "bot@example.com",
                "api_token", "jira-token",
                "project_key", "AF",
                "issue_type", "Bug",
                "triggers", java.util.List.of("QUERY_ESCALATED"),
                "bidirectional_sync", true,
                "webhook_secret", "hook-secret"));
    }

    @Test
    void encodesJiraChannelEncryptsSecretsAndMasksOnRead() {
        var json = codec.encodeForPersistence(NotificationChannelType.JIRA, jiraInput());

        assertThat(json).contains("api_token_encrypted")
                .contains("enc:jira-token")
                .contains("webhook_secret_encrypted")
                .doesNotContain("\"api_token\"")
                .doesNotContain("\"webhook_secret\"");

        var view = codec.decodeForApi(json);
        assertThat(view).containsEntry("api_token", "********")
                .containsEntry("webhook_secret", "********")
                .doesNotContainKey("api_token_encrypted")
                .doesNotContainKey("webhook_secret_encrypted");

        var typed = codec.decodeJira(json);
        assertThat(typed.baseUrl().toString()).isEqualTo("https://example.atlassian.net");
        assertThat(typed.userEmail()).isEqualTo("bot@example.com");
        assertThat(typed.apiTokenPlain()).isEqualTo("jira-token");
        assertThat(typed.projectKey()).isEqualTo("AF");
        assertThat(typed.issueType()).isEqualTo("Bug");
        assertThat(typed.triggers()).containsExactly(TicketingTrigger.QUERY_ESCALATED);
        assertThat(typed.bidirectionalSync()).isTrue();
        assertThat(typed.webhookSecretPlain()).isEqualTo("hook-secret");
    }

    @Test
    void jiraIssueTypeDefaultsToTaskAndSyncDefaultsToFalse() {
        var json = codec.encodeForPersistence(NotificationChannelType.JIRA, Map.of(
                "base_url", "https://example.atlassian.net",
                "user_email", "bot@example.com",
                "api_token", "jira-token",
                "project_key", "AF",
                "triggers", java.util.List.of("QUERY_REJECTED")));

        var typed = codec.decodeJira(json);
        assertThat(typed.issueType()).isEqualTo("Task");
        assertThat(typed.bidirectionalSync()).isFalse();
        assertThat(typed.webhookSecretPlain()).isNull();
        assertThat(typed.approveStatuses())
                .isEqualTo(TicketingChannelConfig.DEFAULT_APPROVE_STATUSES);
        assertThat(typed.rejectStatuses())
                .isEqualTo(TicketingChannelConfig.DEFAULT_REJECT_STATUSES);
    }

    @Test
    void rejectsJiraWithoutRequiredKeys() {
        for (var missing : new String[]{"base_url", "user_email", "project_key", "api_token"}) {
            var input = jiraInput();
            input.remove(missing);
            assertThatThrownBy(() -> codec.encodeForPersistence(
                    NotificationChannelType.JIRA, input))
                    .as("missing " + missing)
                    .isInstanceOf(NotificationChannelConfigException.class)
                    .hasMessageContaining(missing);
        }
    }

    @Test
    void rejectsJiraWithoutTriggersAndSyncWithoutSecret() {
        var noTriggers = jiraInput();
        noTriggers.put("triggers", java.util.List.of());
        assertThatThrownBy(() -> codec.encodeForPersistence(
                NotificationChannelType.JIRA, noTriggers))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("triggers");

        var noSecret = jiraInput();
        noSecret.remove("webhook_secret");
        assertThatThrownBy(() -> codec.encodeForPersistence(
                NotificationChannelType.JIRA, noSecret))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("webhook_secret");
    }

    @Test
    void mergePreservesJiraApiTokenOnMaskAndRotatesOnNewValue() {
        var original = codec.encodeForPersistence(NotificationChannelType.JIRA, jiraInput());

        var kept = codec.mergeForPersistence(NotificationChannelType.JIRA, original, Map.of(
                "api_token", "********"));
        assertThat(codec.decodeJira(kept).apiTokenPlain()).isEqualTo("jira-token");

        var rotated = codec.mergeForPersistence(NotificationChannelType.JIRA, original, Map.of(
                "api_token", "rotated"));
        assertThat(codec.decodeJira(rotated).apiTokenPlain()).isEqualTo("rotated");
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
