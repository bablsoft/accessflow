package com.partqam.accessflow.audit.internal.config;

import com.partqam.accessflow.audit.internal.AuditChainHasher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Wires the audit-module HMAC chain hasher. The active key source is, in order:
 * <ol>
 *   <li>{@code accessflow.audit.hmac-key} (env var {@code AUDIT_HMAC_KEY}) — required for
 *       non-community editions. Must be hex-encoded with at least 32 bytes of entropy.</li>
 *   <li>For community installs only, an HKDF-SHA256 derivative of {@code accessflow.encryption-key}
 *       with the literal info string {@code "accessflow-audit-hmac-v1"}. This keeps the demo /
 *       single-binary deployment path zero-config while still tying the audit chain to a
 *       per-deployment secret that is not stored in the database.</li>
 * </ol>
 * Startup fails fast for any other combination so a misconfigured production deployment cannot
 * silently fall back to a derivable key.
 */
@Configuration
@EnableConfigurationProperties(AuditHmacProperties.class)
@Slf4j
class AuditConfiguration {

    static final String DERIVATION_INFO = "accessflow-audit-hmac-v1";
    private static final int KEY_LENGTH = 32;

    @Bean
    AuditChainHasher auditChainHasher(AuditHmacProperties properties,
                                      @Value("${accessflow.edition:community}") String edition,
                                      @Value("${accessflow.encryption-key:}") String encryptionKey,
                                      ObjectMapper objectMapper,
                                      MessageSource messageSource) {
        byte[] key = resolveKey(properties, edition, encryptionKey, messageSource);
        return new AuditChainHasher(key, objectMapper);
    }

    static byte[] resolveKey(AuditHmacProperties properties, String edition, String encryptionKey,
                             MessageSource messageSource) {
        if (properties != null && properties.hmacKey() != null && !properties.hmacKey().isBlank()) {
            return parseHexKey(properties.hmacKey().trim(), messageSource);
        }
        boolean community = edition == null || edition.isBlank() || "community".equalsIgnoreCase(edition);
        if (!community) {
            throw new IllegalStateException(messageSource.getMessage(
                    "error.audit_hmac_key_missing", null, LocaleContextHolder.getLocale()));
        }
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalStateException(messageSource.getMessage(
                    "error.audit_hmac_key_missing", null, LocaleContextHolder.getLocale()));
        }
        log.warn("AUDIT_HMAC_KEY not set; deriving audit chain key from accessflow.encryption-key. "
                + "Set AUDIT_HMAC_KEY explicitly for production deployments.");
        return deriveFromEncryptionKey(encryptionKey.trim(), messageSource);
    }

    static byte[] deriveFromEncryptionKey(String encryptionKeyHex, MessageSource messageSource) {
        byte[] ikm = parseHexKey(encryptionKeyHex, messageSource);
        return hkdfSha256(ikm, DERIVATION_INFO.getBytes(StandardCharsets.UTF_8), KEY_LENGTH);
    }

    private static byte[] parseHexKey(String hex, MessageSource messageSource) {
        byte[] key;
        try {
            key = HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(messageSource.getMessage(
                    "error.audit_hmac_key_invalid", null, LocaleContextHolder.getLocale()), ex);
        }
        if (key.length < KEY_LENGTH) {
            throw new IllegalStateException(messageSource.getMessage(
                    "error.audit_hmac_key_invalid", null, LocaleContextHolder.getLocale()));
        }
        return key;
    }

    static byte[] hkdfSha256(byte[] ikm, byte[] info, int outputLength) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(new byte[mac.getMacLength()], "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            var okm = new byte[outputLength];
            byte[] previous = new byte[0];
            int written = 0;
            byte counter = 1;
            while (written < outputLength) {
                mac.update(previous);
                mac.update(info);
                mac.update(counter);
                previous = mac.doFinal();
                int copy = Math.min(previous.length, outputLength - written);
                System.arraycopy(previous, 0, okm, written, copy);
                written += copy;
                counter++;
            }
            return okm;
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable for HKDF", ex);
        }
    }
}
