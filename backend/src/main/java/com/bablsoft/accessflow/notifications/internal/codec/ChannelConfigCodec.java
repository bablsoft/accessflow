package com.bablsoft.accessflow.notifications.internal.codec;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.notifications.api.NotificationChannelConfigException;
import com.bablsoft.accessflow.notifications.api.NotificationChannelType;
import com.bablsoft.accessflow.notifications.api.NotificationChannelView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads and writes the JSONB {@code notification_channels.config} blob.
 *
 * <p>Inbound API uses unsuffixed sensitive keys ({@code smtp_password}, {@code secret},
 * {@code bot_token}) which the codec encrypts via {@link CredentialEncryptionService} and stores
 * under the suffixed keys ({@code smtp_password_encrypted}, {@code secret_encrypted},
 * {@code bot_token_encrypted}). On read for API the codec replaces the encrypted keys with the
 * masked placeholder under the unsuffixed names. On read for dispatch the codec returns a typed
 * {@link EmailChannelConfig} / {@link SlackChannelConfig} / {@link WebhookChannelConfig} /
 * {@link DiscordChannelConfig} / {@link TelegramChannelConfig} / {@link MsTeamsChannelConfig} /
 * {@link PagerDutyChannelConfig} with decrypted secrets.
 */
@Component
@RequiredArgsConstructor
public class ChannelConfigCodec {

    private static final String MASK = NotificationChannelView.DefaultMaskedPlaceholder.VALUE;

    static final String KEY_SMTP_HOST = "smtp_host";
    static final String KEY_SMTP_PORT = "smtp_port";
    static final String KEY_SMTP_USER = "smtp_user";
    static final String KEY_SMTP_PASSWORD = "smtp_password";
    static final String KEY_SMTP_PASSWORD_ENCRYPTED = "smtp_password_encrypted";
    static final String KEY_SMTP_TLS = "smtp_tls";
    static final String KEY_FROM_ADDRESS = "from_address";
    static final String KEY_FROM_NAME = "from_name";

    static final String KEY_WEBHOOK_URL = "webhook_url";
    static final String KEY_CHANNEL = "channel";
    static final String KEY_MENTION_USERS = "mention_users";

    static final String KEY_URL = "url";
    static final String KEY_SECRET = "secret";
    static final String KEY_SECRET_ENCRYPTED = "secret_encrypted";
    static final String KEY_TIMEOUT_SECONDS = "timeout_seconds";

    static final String KEY_DISCORD_USERNAME = "username";
    static final String KEY_DISCORD_AVATAR_URL = "avatar_url";

    static final String KEY_BOT_TOKEN = "bot_token";
    static final String KEY_BOT_TOKEN_ENCRYPTED = "bot_token_encrypted";
    static final String KEY_CHAT_ID = "chat_id";

    static final String KEY_ROUTING_KEY = "routing_key";
    static final String KEY_ROUTING_KEY_ENCRYPTED = "routing_key_encrypted";
    static final String KEY_DEFAULT_SEVERITY = "default_severity";
    static final String KEY_TRIGGERS = "triggers";

    static final String KEY_INSTANCE_URL = "instance_url";
    static final String KEY_TICKET_USERNAME = "username";
    static final String KEY_PASSWORD = "password";
    static final String KEY_PASSWORD_ENCRYPTED = "password_encrypted";
    static final String KEY_ASSIGNMENT_GROUP = "assignment_group";
    static final String KEY_URGENCY = "urgency";
    static final String KEY_BASE_URL = "base_url";
    static final String KEY_USER_EMAIL = "user_email";
    static final String KEY_API_TOKEN = "api_token";
    static final String KEY_API_TOKEN_ENCRYPTED = "api_token_encrypted";
    static final String KEY_PROJECT_KEY = "project_key";
    static final String KEY_ISSUE_TYPE = "issue_type";
    static final String KEY_BIDIRECTIONAL_SYNC = "bidirectional_sync";
    static final String KEY_WEBHOOK_SECRET = "webhook_secret";
    static final String KEY_WEBHOOK_SECRET_ENCRYPTED = "webhook_secret_encrypted";
    static final String KEY_APPROVE_STATUSES = "approve_statuses";
    static final String KEY_REJECT_STATUSES = "reject_statuses";

    static final String DEFAULT_JIRA_ISSUE_TYPE = "Task";

    private final ObjectMapper objectMapper;
    private final CredentialEncryptionService encryptionService;

    /**
     * Build the JSON string to persist for a freshly created channel.
     */
    public String encodeForPersistence(NotificationChannelType type, Map<String, Object> input) {
        var config = sanitizeInput(input);
        switch (type) {
            case EMAIL -> validateEmail(config);
            case SLACK -> validateSlack(config);
            case WEBHOOK -> validateWebhook(config);
            case DISCORD -> validateDiscord(config);
            case TELEGRAM -> validateTelegram(config);
            case MS_TEAMS -> validateMsTeams(config);
            case PAGERDUTY -> validatePagerDuty(config);
            case SERVICENOW -> validateServiceNow(config);
            case JIRA -> validateJira(config);
        }
        encryptSensitive(config);
        return writeJson(config);
    }

    /**
     * Merge an existing persisted JSON with a partial update. Sensitive fields shown as the
     * masked placeholder are preserved from the existing ciphertext.
     */
    public String mergeForPersistence(NotificationChannelType type, String existingJson,
                                      Map<String, Object> partial) {
        if (partial == null) {
            return existingJson;
        }
        var existing = readJson(existingJson);
        var sanitized = sanitizeInput(partial);
        for (var entry : sanitized.entrySet()) {
            existing.put(entry.getKey(), entry.getValue());
        }
        passThroughMaskedSecrets(existing);
        switch (type) {
            case EMAIL -> validateEmail(existing);
            case SLACK -> validateSlack(existing);
            case WEBHOOK -> validateWebhook(existing);
            case DISCORD -> validateDiscord(existing);
            case TELEGRAM -> validateTelegram(existing);
            case MS_TEAMS -> validateMsTeams(existing);
            case PAGERDUTY -> validatePagerDuty(existing);
            case SERVICENOW -> validateServiceNow(existing);
            case JIRA -> validateJira(existing);
        }
        encryptSensitive(existing);
        return writeJson(existing);
    }

    /**
     * Build a masked, API-safe view of the persisted config.
     */
    public Map<String, Object> decodeForApi(String storedJson) {
        var stored = readJson(storedJson);
        var view = new LinkedHashMap<String, Object>(stored);
        if (view.containsKey(KEY_SMTP_PASSWORD_ENCRYPTED)) {
            view.remove(KEY_SMTP_PASSWORD_ENCRYPTED);
            view.put(KEY_SMTP_PASSWORD, MASK);
        }
        if (view.containsKey(KEY_SECRET_ENCRYPTED)) {
            view.remove(KEY_SECRET_ENCRYPTED);
            view.put(KEY_SECRET, MASK);
        }
        if (view.containsKey(KEY_BOT_TOKEN_ENCRYPTED)) {
            view.remove(KEY_BOT_TOKEN_ENCRYPTED);
            view.put(KEY_BOT_TOKEN, MASK);
        }
        if (view.containsKey(KEY_ROUTING_KEY_ENCRYPTED)) {
            view.remove(KEY_ROUTING_KEY_ENCRYPTED);
            view.put(KEY_ROUTING_KEY, MASK);
        }
        if (view.containsKey(KEY_PASSWORD_ENCRYPTED)) {
            view.remove(KEY_PASSWORD_ENCRYPTED);
            view.put(KEY_PASSWORD, MASK);
        }
        if (view.containsKey(KEY_API_TOKEN_ENCRYPTED)) {
            view.remove(KEY_API_TOKEN_ENCRYPTED);
            view.put(KEY_API_TOKEN, MASK);
        }
        if (view.containsKey(KEY_WEBHOOK_SECRET_ENCRYPTED)) {
            view.remove(KEY_WEBHOOK_SECRET_ENCRYPTED);
            view.put(KEY_WEBHOOK_SECRET, MASK);
        }
        return view;
    }

    public EmailChannelConfig decodeEmail(String storedJson) {
        var c = readJson(storedJson);
        var encrypted = stringOrNull(c.get(KEY_SMTP_PASSWORD_ENCRYPTED));
        var smtpPasswordPlain = encrypted != null ? encryptionService.decrypt(encrypted) : null;
        return new EmailChannelConfig(
                requireString(c, KEY_SMTP_HOST),
                requireInt(c, KEY_SMTP_PORT),
                stringOrNull(c.get(KEY_SMTP_USER)),
                smtpPasswordPlain,
                booleanOrDefault(c.get(KEY_SMTP_TLS), true),
                requireString(c, KEY_FROM_ADDRESS),
                stringOrNull(c.get(KEY_FROM_NAME)));
    }

    public SlackChannelConfig decodeSlack(String storedJson) {
        var c = readJson(storedJson);
        return new SlackChannelConfig(
                requireUri(c, KEY_WEBHOOK_URL),
                stringOrNull(c.get(KEY_CHANNEL)),
                stringList(c.get(KEY_MENTION_USERS)));
    }

    public WebhookChannelConfig decodeWebhook(String storedJson) {
        var c = readJson(storedJson);
        var encrypted = stringOrNull(c.get(KEY_SECRET_ENCRYPTED));
        var secretPlain = encrypted != null ? encryptionService.decrypt(encrypted) : null;
        return new WebhookChannelConfig(
                requireUri(c, KEY_URL),
                secretPlain,
                intOrDefault(c.get(KEY_TIMEOUT_SECONDS), 10));
    }

    public DiscordChannelConfig decodeDiscord(String storedJson) {
        var c = readJson(storedJson);
        return new DiscordChannelConfig(
                requireUri(c, KEY_WEBHOOK_URL),
                stringOrNull(c.get(KEY_DISCORD_USERNAME)),
                stringOrNull(c.get(KEY_DISCORD_AVATAR_URL)));
    }

    public TelegramChannelConfig decodeTelegram(String storedJson) {
        var c = readJson(storedJson);
        var encrypted = stringOrNull(c.get(KEY_BOT_TOKEN_ENCRYPTED));
        var botTokenPlain = encrypted != null ? encryptionService.decrypt(encrypted) : null;
        return new TelegramChannelConfig(
                botTokenPlain,
                requireString(c, KEY_CHAT_ID));
    }

    public MsTeamsChannelConfig decodeMsTeams(String storedJson) {
        var c = readJson(storedJson);
        return new MsTeamsChannelConfig(requireUri(c, KEY_WEBHOOK_URL));
    }

    public PagerDutyChannelConfig decodePagerDuty(String storedJson) {
        var c = readJson(storedJson);
        var encrypted = stringOrNull(c.get(KEY_ROUTING_KEY_ENCRYPTED));
        var routingKeyPlain = encrypted != null ? encryptionService.decrypt(encrypted) : null;
        var triggers = stringList(c.get(KEY_TRIGGERS)).stream()
                .map(PagerDutyTrigger::fromConfig)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(PagerDutyTrigger.class)));
        return new PagerDutyChannelConfig(
                routingKeyPlain,
                PagerDutySeverity.fromWire(requireString(c, KEY_DEFAULT_SEVERITY)),
                triggers);
    }

    public ServiceNowChannelConfig decodeServiceNow(String storedJson) {
        var c = readJson(storedJson);
        return new ServiceNowChannelConfig(
                requireUri(c, KEY_INSTANCE_URL),
                requireString(c, KEY_TICKET_USERNAME),
                decryptOrNull(c, KEY_PASSWORD_ENCRYPTED),
                stringOrNull(c.get(KEY_ASSIGNMENT_GROUP)),
                c.get(KEY_URGENCY) == null ? null : requireInt(c, KEY_URGENCY),
                ticketingTriggers(c),
                booleanOrDefault(c.get(KEY_BIDIRECTIONAL_SYNC), false),
                decryptOrNull(c, KEY_WEBHOOK_SECRET_ENCRYPTED),
                statusList(c.get(KEY_APPROVE_STATUSES),
                        TicketingChannelConfig.DEFAULT_APPROVE_STATUSES),
                statusList(c.get(KEY_REJECT_STATUSES),
                        TicketingChannelConfig.DEFAULT_REJECT_STATUSES));
    }

    public JiraChannelConfig decodeJira(String storedJson) {
        var c = readJson(storedJson);
        var issueType = stringOrNull(c.get(KEY_ISSUE_TYPE));
        return new JiraChannelConfig(
                requireUri(c, KEY_BASE_URL),
                requireString(c, KEY_USER_EMAIL),
                decryptOrNull(c, KEY_API_TOKEN_ENCRYPTED),
                requireString(c, KEY_PROJECT_KEY),
                issueType == null || issueType.isBlank() ? DEFAULT_JIRA_ISSUE_TYPE : issueType,
                ticketingTriggers(c),
                booleanOrDefault(c.get(KEY_BIDIRECTIONAL_SYNC), false),
                decryptOrNull(c, KEY_WEBHOOK_SECRET_ENCRYPTED),
                statusList(c.get(KEY_APPROVE_STATUSES),
                        TicketingChannelConfig.DEFAULT_APPROVE_STATUSES),
                statusList(c.get(KEY_REJECT_STATUSES),
                        TicketingChannelConfig.DEFAULT_REJECT_STATUSES));
    }

    private String decryptOrNull(Map<String, Object> config, String encryptedKey) {
        var encrypted = stringOrNull(config.get(encryptedKey));
        return encrypted != null ? encryptionService.decrypt(encrypted) : null;
    }

    private Set<TicketingTrigger> ticketingTriggers(Map<String, Object> config) {
        return stringList(config.get(KEY_TRIGGERS)).stream()
                .map(TicketingTrigger::fromConfig)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(TicketingTrigger.class)));
    }

    private static List<String> statusList(Object value, List<String> defaults) {
        var list = stringList(value);
        return list.isEmpty() ? defaults : list;
    }

    private Map<String, Object> sanitizeInput(Map<String, Object> input) {
        if (input == null) {
            return new LinkedHashMap<>();
        }
        var copy = new LinkedHashMap<String, Object>();
        for (var entry : input.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    private void passThroughMaskedSecrets(Map<String, Object> config) {
        if (MASK.equals(config.get(KEY_SMTP_PASSWORD))) {
            config.remove(KEY_SMTP_PASSWORD);
        }
        if (MASK.equals(config.get(KEY_SECRET))) {
            config.remove(KEY_SECRET);
        }
        if (MASK.equals(config.get(KEY_BOT_TOKEN))) {
            config.remove(KEY_BOT_TOKEN);
        }
        if (MASK.equals(config.get(KEY_ROUTING_KEY))) {
            config.remove(KEY_ROUTING_KEY);
        }
        if (MASK.equals(config.get(KEY_PASSWORD))) {
            config.remove(KEY_PASSWORD);
        }
        if (MASK.equals(config.get(KEY_API_TOKEN))) {
            config.remove(KEY_API_TOKEN);
        }
        if (MASK.equals(config.get(KEY_WEBHOOK_SECRET))) {
            config.remove(KEY_WEBHOOK_SECRET);
        }
    }

    private void encryptSensitive(Map<String, Object> config) {
        var smtpPassword = stringOrNull(config.remove(KEY_SMTP_PASSWORD));
        if (smtpPassword != null && !smtpPassword.isBlank()) {
            config.put(KEY_SMTP_PASSWORD_ENCRYPTED, encryptionService.encrypt(smtpPassword));
        }
        var secret = stringOrNull(config.remove(KEY_SECRET));
        if (secret != null && !secret.isBlank()) {
            config.put(KEY_SECRET_ENCRYPTED, encryptionService.encrypt(secret));
        }
        var botToken = stringOrNull(config.remove(KEY_BOT_TOKEN));
        if (botToken != null && !botToken.isBlank()) {
            config.put(KEY_BOT_TOKEN_ENCRYPTED, encryptionService.encrypt(botToken));
        }
        var routingKey = stringOrNull(config.remove(KEY_ROUTING_KEY));
        if (routingKey != null && !routingKey.isBlank()) {
            config.put(KEY_ROUTING_KEY_ENCRYPTED, encryptionService.encrypt(routingKey));
        }
        var password = stringOrNull(config.remove(KEY_PASSWORD));
        if (password != null && !password.isBlank()) {
            config.put(KEY_PASSWORD_ENCRYPTED, encryptionService.encrypt(password));
        }
        var apiToken = stringOrNull(config.remove(KEY_API_TOKEN));
        if (apiToken != null && !apiToken.isBlank()) {
            config.put(KEY_API_TOKEN_ENCRYPTED, encryptionService.encrypt(apiToken));
        }
        var webhookSecret = stringOrNull(config.remove(KEY_WEBHOOK_SECRET));
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            config.put(KEY_WEBHOOK_SECRET_ENCRYPTED, encryptionService.encrypt(webhookSecret));
        }
    }

    private void validateEmail(Map<String, Object> config) {
        requireString(config, KEY_SMTP_HOST);
        requireInt(config, KEY_SMTP_PORT);
        requireString(config, KEY_FROM_ADDRESS);
        var hasPlaintext = stringOrNull(config.get(KEY_SMTP_PASSWORD)) != null;
        var hasCipher = stringOrNull(config.get(KEY_SMTP_PASSWORD_ENCRYPTED)) != null;
        if (!hasPlaintext && !hasCipher) {
            throw new NotificationChannelConfigException(
                    "Email channel config requires '" + KEY_SMTP_PASSWORD + "'");
        }
    }

    private void validateSlack(Map<String, Object> config) {
        requireUri(config, KEY_WEBHOOK_URL);
    }

    private void validateWebhook(Map<String, Object> config) {
        requireUri(config, KEY_URL);
        var hasPlaintext = stringOrNull(config.get(KEY_SECRET)) != null;
        var hasCipher = stringOrNull(config.get(KEY_SECRET_ENCRYPTED)) != null;
        if (!hasPlaintext && !hasCipher) {
            throw new NotificationChannelConfigException(
                    "Webhook channel config requires '" + KEY_SECRET + "'");
        }
    }

    private void validateDiscord(Map<String, Object> config) {
        requireUri(config, KEY_WEBHOOK_URL);
    }

    private void validateTelegram(Map<String, Object> config) {
        requireString(config, KEY_CHAT_ID);
        var hasPlaintext = stringOrNull(config.get(KEY_BOT_TOKEN)) != null;
        var hasCipher = stringOrNull(config.get(KEY_BOT_TOKEN_ENCRYPTED)) != null;
        if (!hasPlaintext && !hasCipher) {
            throw new NotificationChannelConfigException(
                    "Telegram channel config requires '" + KEY_BOT_TOKEN + "'");
        }
    }

    private void validateMsTeams(Map<String, Object> config) {
        requireUri(config, KEY_WEBHOOK_URL);
    }

    private void validatePagerDuty(Map<String, Object> config) {
        var hasPlaintext = stringOrNull(config.get(KEY_ROUTING_KEY)) != null;
        var hasCipher = stringOrNull(config.get(KEY_ROUTING_KEY_ENCRYPTED)) != null;
        if (!hasPlaintext && !hasCipher) {
            throw new NotificationChannelConfigException(
                    "PagerDuty channel config requires '" + KEY_ROUTING_KEY + "'");
        }
        PagerDutySeverity.fromWire(requireString(config, KEY_DEFAULT_SEVERITY));
        var triggers = stringList(config.get(KEY_TRIGGERS));
        if (triggers.isEmpty()) {
            throw new NotificationChannelConfigException(
                    "PagerDuty channel config requires at least one '" + KEY_TRIGGERS + "'");
        }
        triggers.forEach(PagerDutyTrigger::fromConfig);
    }

    private void validateServiceNow(Map<String, Object> config) {
        requireUri(config, KEY_INSTANCE_URL);
        requireString(config, KEY_TICKET_USERNAME);
        var hasPlaintext = stringOrNull(config.get(KEY_PASSWORD)) != null;
        var hasCipher = stringOrNull(config.get(KEY_PASSWORD_ENCRYPTED)) != null;
        if (!hasPlaintext && !hasCipher) {
            throw new NotificationChannelConfigException(
                    "ServiceNow channel config requires '" + KEY_PASSWORD + "'");
        }
        if (config.get(KEY_URGENCY) != null) {
            var urgency = requireInt(config, KEY_URGENCY);
            if (urgency < 1 || urgency > 3) {
                throw new NotificationChannelConfigException(
                        "Config key '" + KEY_URGENCY + "' must be between 1 and 3");
            }
        }
        validateTicketingCommon(config, "ServiceNow");
    }

    private void validateJira(Map<String, Object> config) {
        requireUri(config, KEY_BASE_URL);
        requireString(config, KEY_USER_EMAIL);
        requireString(config, KEY_PROJECT_KEY);
        var hasPlaintext = stringOrNull(config.get(KEY_API_TOKEN)) != null;
        var hasCipher = stringOrNull(config.get(KEY_API_TOKEN_ENCRYPTED)) != null;
        if (!hasPlaintext && !hasCipher) {
            throw new NotificationChannelConfigException(
                    "Jira channel config requires '" + KEY_API_TOKEN + "'");
        }
        validateTicketingCommon(config, "Jira");
    }

    private void validateTicketingCommon(Map<String, Object> config, String label) {
        var triggers = stringList(config.get(KEY_TRIGGERS));
        if (triggers.isEmpty()) {
            throw new NotificationChannelConfigException(
                    label + " channel config requires at least one '" + KEY_TRIGGERS + "'");
        }
        triggers.forEach(TicketingTrigger::fromConfig);
        if (booleanOrDefault(config.get(KEY_BIDIRECTIONAL_SYNC), false)) {
            var hasPlaintext = stringOrNull(config.get(KEY_WEBHOOK_SECRET)) != null;
            var hasCipher = stringOrNull(config.get(KEY_WEBHOOK_SECRET_ENCRYPTED)) != null;
            if (!hasPlaintext && !hasCipher) {
                throw new NotificationChannelConfigException(
                        label + " channel config requires '" + KEY_WEBHOOK_SECRET
                                + "' when '" + KEY_BIDIRECTIONAL_SYNC + "' is enabled");
            }
        }
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            @SuppressWarnings("unchecked")
            var parsed = (Map<String, Object>) objectMapper.readValue(json, Map.class);
            return new LinkedHashMap<>(parsed);
        } catch (RuntimeException ex) {
            throw new NotificationChannelConfigException("Stored channel config is not valid JSON", ex);
        }
    }

    private String writeJson(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (RuntimeException ex) {
            throw new NotificationChannelConfigException("Failed to serialize channel config", ex);
        }
    }

    private static String requireString(Map<String, Object> config, String key) {
        var value = stringOrNull(config.get(key));
        if (value == null || value.isBlank()) {
            throw new NotificationChannelConfigException("Missing required config key: " + key);
        }
        return value;
    }

    private static int requireInt(Map<String, Object> config, String key) {
        var raw = config.get(key);
        if (raw == null) {
            throw new NotificationChannelConfigException("Missing required config key: " + key);
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException ex) {
            throw new NotificationChannelConfigException(
                    "Config key '" + key + "' must be numeric", ex);
        }
    }

    private static URI requireUri(Map<String, Object> config, String key) {
        var raw = stringOrNull(config.get(key));
        if (raw == null || raw.isBlank()) {
            throw new NotificationChannelConfigException("Missing required config key: " + key);
        }
        try {
            return new URI(raw);
        } catch (URISyntaxException ex) {
            throw new NotificationChannelConfigException(
                    "Config key '" + key + "' is not a valid URI", ex);
        }
    }

    private static String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString();
    }

    private static boolean booleanOrDefault(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private static int intOrDefault(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> raw) {
            return raw.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        return List.of(value.toString());
    }
}
