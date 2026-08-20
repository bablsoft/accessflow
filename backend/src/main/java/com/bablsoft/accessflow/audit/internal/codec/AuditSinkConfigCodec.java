package com.bablsoft.accessflow.audit.internal.codec;

import com.bablsoft.accessflow.audit.api.AuditSinkConfigException;
import com.bablsoft.accessflow.audit.api.AuditSinkType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and writes the JSONB {@code audit_sinks.config} blob (#628), mirroring the
 * notification-channel codec contract: inbound API uses unsuffixed sensitive keys
 * ({@code token}, {@code secret}, {@code secret_access_key}) which the codec encrypts via
 * {@link CredentialEncryptionService} and stores under {@code <key>_encrypted}; on read for API
 * the encrypted keys are replaced with the masked placeholder under the unsuffixed names; on
 * read for dispatch the codec returns a typed config record with decrypted secrets.
 */
@Component
@RequiredArgsConstructor
public class AuditSinkConfigCodec {

    /** Masked placeholder returned in API views; echoing it back preserves the ciphertext. */
    public static final String MASK = "********";

    static final String KEY_URL = "url";
    static final String KEY_TOKEN = "token";
    static final String KEY_TOKEN_ENCRYPTED = "token_encrypted";
    static final String KEY_INDEX = "index";
    static final String KEY_SOURCE = "source";

    static final String KEY_HOST = "host";
    static final String KEY_PORT = "port";
    static final String KEY_PROTOCOL = "protocol";
    static final String PROTOCOL_TCP = "TCP";
    static final String PROTOCOL_TLS = "TLS";

    static final String KEY_SECRET = "secret";
    static final String KEY_SECRET_ENCRYPTED = "secret_encrypted";

    static final String KEY_BUCKET = "bucket";
    static final String KEY_REGION = "region";
    static final String KEY_PREFIX = "prefix";
    static final String KEY_ACCESS_KEY_ID = "access_key_id";
    static final String KEY_SECRET_ACCESS_KEY = "secret_access_key";
    static final String KEY_SECRET_ACCESS_KEY_ENCRYPTED = "secret_access_key_encrypted";
    static final String KEY_ENDPOINT = "endpoint";
    static final String KEY_RETENTION_MODE = "retention_mode";
    static final String KEY_RETENTION_DAYS = "retention_days";
    static final String KEY_SEGMENT_MAX_AGE = "segment_max_age";

    private final ObjectMapper objectMapper;
    private final CredentialEncryptionService encryptionService;

    /** Build the JSON string to persist for a freshly created sink. */
    public String encodeForPersistence(AuditSinkType type, Map<String, Object> input) {
        var config = sanitizeInput(input);
        validate(type, config);
        encryptSensitive(config);
        return writeJson(config);
    }

    /**
     * Merge an existing persisted JSON with a partial update. Sensitive fields shown as the
     * masked placeholder are preserved from the existing ciphertext.
     */
    public String mergeForPersistence(AuditSinkType type, String existingJson,
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
        validate(type, existing);
        encryptSensitive(existing);
        return writeJson(existing);
    }

    /** Build a masked, API-safe view of the persisted config. */
    public Map<String, Object> decodeForApi(String storedJson) {
        var view = new LinkedHashMap<String, Object>(readJson(storedJson));
        maskEncrypted(view, KEY_TOKEN_ENCRYPTED, KEY_TOKEN);
        maskEncrypted(view, KEY_SECRET_ENCRYPTED, KEY_SECRET);
        maskEncrypted(view, KEY_SECRET_ACCESS_KEY_ENCRYPTED, KEY_SECRET_ACCESS_KEY);
        return view;
    }

    public SplunkHecSinkConfig decodeSplunkHec(String storedJson) {
        var c = readJson(storedJson);
        var source = stringOrNull(c.get(KEY_SOURCE));
        return new SplunkHecSinkConfig(
                requireUri(c, KEY_URL),
                decryptOrNull(c, KEY_TOKEN_ENCRYPTED),
                stringOrNull(c.get(KEY_INDEX)),
                source == null || source.isBlank() ? SplunkHecSinkConfig.DEFAULT_SOURCE : source);
    }

    public SyslogCefSinkConfig decodeSyslogCef(String storedJson) {
        var c = readJson(storedJson);
        return new SyslogCefSinkConfig(
                requireString(c, KEY_HOST),
                requirePort(c),
                PROTOCOL_TLS.equalsIgnoreCase(requireString(c, KEY_PROTOCOL)));
    }

    public HttpsBatchSinkConfig decodeHttpsBatch(String storedJson) {
        var c = readJson(storedJson);
        return new HttpsBatchSinkConfig(
                requireUri(c, KEY_URL),
                decryptOrNull(c, KEY_SECRET_ENCRYPTED));
    }

    public S3ObjectLockSinkConfig decodeS3ObjectLock(String storedJson) {
        var c = readJson(storedJson);
        return new S3ObjectLockSinkConfig(
                requireString(c, KEY_BUCKET),
                requireString(c, KEY_REGION),
                normalizedPrefix(stringOrNull(c.get(KEY_PREFIX))),
                requireString(c, KEY_ACCESS_KEY_ID),
                decryptOrNull(c, KEY_SECRET_ACCESS_KEY_ENCRYPTED),
                stringOrNull(c.get(KEY_ENDPOINT)),
                retentionMode(c),
                requirePositiveInt(c, KEY_RETENTION_DAYS),
                segmentMaxAge(c));
    }

    private void validate(AuditSinkType type, Map<String, Object> config) {
        switch (type) {
            case SPLUNK_HEC -> validateSplunkHec(config);
            case SYSLOG_CEF -> validateSyslogCef(config);
            case HTTPS_BATCH -> validateHttpsBatch(config);
            case S3_OBJECT_LOCK -> validateS3ObjectLock(config);
        }
    }

    private void validateSplunkHec(Map<String, Object> config) {
        requireUri(config, KEY_URL);
        requireSecret(config, KEY_TOKEN, KEY_TOKEN_ENCRYPTED);
    }

    private void validateSyslogCef(Map<String, Object> config) {
        requireString(config, KEY_HOST);
        requirePort(config);
        var protocol = requireString(config, KEY_PROTOCOL);
        if (!PROTOCOL_TCP.equalsIgnoreCase(protocol) && !PROTOCOL_TLS.equalsIgnoreCase(protocol)) {
            throw new AuditSinkConfigException(
                    "Config key '" + KEY_PROTOCOL + "' must be TCP or TLS");
        }
    }

    private void validateHttpsBatch(Map<String, Object> config) {
        requireUri(config, KEY_URL);
        requireSecret(config, KEY_SECRET, KEY_SECRET_ENCRYPTED);
    }

    private void validateS3ObjectLock(Map<String, Object> config) {
        requireString(config, KEY_BUCKET);
        requireString(config, KEY_REGION);
        requireString(config, KEY_ACCESS_KEY_ID);
        requireSecret(config, KEY_SECRET_ACCESS_KEY, KEY_SECRET_ACCESS_KEY_ENCRYPTED);
        requirePositiveInt(config, KEY_RETENTION_DAYS);
        retentionMode(config);
        segmentMaxAge(config);
    }

    private static void requireSecret(Map<String, Object> config, String plainKey,
                                      String encryptedKey) {
        var hasPlaintext = stringOrNull(config.get(plainKey)) != null;
        var hasCipher = stringOrNull(config.get(encryptedKey)) != null;
        if (!hasPlaintext && !hasCipher) {
            throw new AuditSinkConfigException("Missing required config key: " + plainKey);
        }
    }

    private void passThroughMaskedSecrets(Map<String, Object> config) {
        if (MASK.equals(config.get(KEY_TOKEN))) {
            config.remove(KEY_TOKEN);
        }
        if (MASK.equals(config.get(KEY_SECRET))) {
            config.remove(KEY_SECRET);
        }
        if (MASK.equals(config.get(KEY_SECRET_ACCESS_KEY))) {
            config.remove(KEY_SECRET_ACCESS_KEY);
        }
    }

    private void encryptSensitive(Map<String, Object> config) {
        encryptKey(config, KEY_TOKEN, KEY_TOKEN_ENCRYPTED);
        encryptKey(config, KEY_SECRET, KEY_SECRET_ENCRYPTED);
        encryptKey(config, KEY_SECRET_ACCESS_KEY, KEY_SECRET_ACCESS_KEY_ENCRYPTED);
    }

    private void encryptKey(Map<String, Object> config, String plainKey, String encryptedKey) {
        var plaintext = stringOrNull(config.remove(plainKey));
        if (plaintext != null && !plaintext.isBlank()) {
            config.put(encryptedKey, encryptionService.encrypt(plaintext));
        }
    }

    private static void maskEncrypted(Map<String, Object> view, String encryptedKey,
                                      String plainKey) {
        if (view.containsKey(encryptedKey)) {
            view.remove(encryptedKey);
            view.put(plainKey, MASK);
        }
    }

    private String decryptOrNull(Map<String, Object> config, String encryptedKey) {
        var encrypted = stringOrNull(config.get(encryptedKey));
        return encrypted != null ? encryptionService.decrypt(encrypted) : null;
    }

    private static S3ObjectLockSinkConfig.RetentionMode retentionMode(Map<String, Object> config) {
        var raw = stringOrNull(config.get(KEY_RETENTION_MODE));
        if (raw == null || raw.isBlank()) {
            return S3ObjectLockSinkConfig.RetentionMode.COMPLIANCE;
        }
        try {
            return S3ObjectLockSinkConfig.RetentionMode.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new AuditSinkConfigException(
                    "Config key '" + KEY_RETENTION_MODE + "' must be COMPLIANCE or GOVERNANCE", ex);
        }
    }

    private static Duration segmentMaxAge(Map<String, Object> config) {
        var raw = stringOrNull(config.get(KEY_SEGMENT_MAX_AGE));
        if (raw == null || raw.isBlank()) {
            return S3ObjectLockSinkConfig.DEFAULT_SEGMENT_MAX_AGE;
        }
        Duration parsed;
        try {
            parsed = Duration.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new AuditSinkConfigException(
                    "Config key '" + KEY_SEGMENT_MAX_AGE + "' must be an ISO-8601 duration", ex);
        }
        if (parsed.isNegative() || parsed.isZero()) {
            throw new AuditSinkConfigException(
                    "Config key '" + KEY_SEGMENT_MAX_AGE + "' must be positive");
        }
        return parsed;
    }

    private static int requirePort(Map<String, Object> config) {
        var port = requireInt(config, KEY_PORT);
        if (port < 1 || port > 65535) {
            throw new AuditSinkConfigException(
                    "Config key '" + KEY_PORT + "' must be between 1 and 65535");
        }
        return port;
    }

    private static int requirePositiveInt(Map<String, Object> config, String key) {
        var value = requireInt(config, key);
        if (value < 1) {
            throw new AuditSinkConfigException("Config key '" + key + "' must be at least 1");
        }
        return value;
    }

    private static String normalizedPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return S3ObjectLockSinkConfig.DEFAULT_PREFIX;
        }
        var trimmed = prefix.strip();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    private Map<String, Object> sanitizeInput(Map<String, Object> input) {
        var copy = new LinkedHashMap<String, Object>();
        if (input == null) {
            return copy;
        }
        for (var entry : input.entrySet()) {
            // Clients may never supply ciphertext directly: an attacker-chosen (or stale)
            // *_encrypted value would pass validation as "cipher present" and then fail
            // decryption on every drain tick. Only the codec writes encrypted keys.
            if (entry.getKey() != null && !entry.getKey().endsWith("_encrypted")) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy;
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
            throw new AuditSinkConfigException("Stored sink config is not valid JSON", ex);
        }
    }

    private String writeJson(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (RuntimeException ex) {
            throw new AuditSinkConfigException("Failed to serialize sink config", ex);
        }
    }

    private static String requireString(Map<String, Object> config, String key) {
        var value = stringOrNull(config.get(key));
        if (value == null || value.isBlank()) {
            throw new AuditSinkConfigException("Missing required config key: " + key);
        }
        return value;
    }

    private static int requireInt(Map<String, Object> config, String key) {
        var raw = config.get(key);
        if (raw == null) {
            throw new AuditSinkConfigException("Missing required config key: " + key);
        }
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(raw.toString().trim());
        } catch (NumberFormatException ex) {
            throw new AuditSinkConfigException("Config key '" + key + "' must be numeric", ex);
        }
    }

    private static URI requireUri(Map<String, Object> config, String key) {
        var raw = stringOrNull(config.get(key));
        if (raw == null || raw.isBlank()) {
            throw new AuditSinkConfigException("Missing required config key: " + key);
        }
        try {
            return new URI(raw);
        } catch (URISyntaxException ex) {
            throw new AuditSinkConfigException("Config key '" + key + "' is not a valid URI", ex);
        }
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }
}
