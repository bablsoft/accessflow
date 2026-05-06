package com.partqam.accessflow.notifications.internal.codec;

import com.partqam.accessflow.core.api.CredentialEncryptionService;
import com.partqam.accessflow.notifications.api.NotificationChannelConfigException;
import com.partqam.accessflow.notifications.api.NotificationChannelType;
import com.partqam.accessflow.notifications.api.NotificationChannelView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads and writes the JSONB {@code notification_channels.config} blob.
 *
 * <p>Inbound API uses unsuffixed sensitive keys ({@code smtp_password}, {@code secret}) which the
 * codec encrypts via {@link CredentialEncryptionService} and stores under the suffixed keys
 * ({@code smtp_password_encrypted}, {@code secret_encrypted}). On read for API the codec replaces
 * the encrypted keys with the masked placeholder under the unsuffixed names. On read for dispatch
 * the codec returns a typed {@link EmailChannelConfig} / {@link SlackChannelConfig} /
 * {@link WebhookChannelConfig} with decrypted secrets.
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
