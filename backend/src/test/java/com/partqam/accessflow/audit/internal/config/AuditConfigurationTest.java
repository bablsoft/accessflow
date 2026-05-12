package com.partqam.accessflow.audit.internal.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditConfigurationTest {

    private final MessageSource messageSource = createMessageSource();
    private static final String VALID_HEX_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SAMPLE_ENCRYPTION_KEY =
            "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff0001020304050607080910111213141f";

    @Test
    void usesExplicitHmacKeyWhenProvided() {
        var key = AuditConfiguration.resolveKey(
                new AuditHmacProperties(VALID_HEX_KEY),
                "enterprise",
                SAMPLE_ENCRYPTION_KEY,
                messageSource);
        assertThat(key).hasSize(32);
    }

    @Test
    void trimsWhitespaceFromExplicitKey() {
        var key = AuditConfiguration.resolveKey(
                new AuditHmacProperties("  " + VALID_HEX_KEY + "\n"),
                "enterprise",
                null,
                messageSource);
        assertThat(key).hasSize(32);
    }

    @Test
    void rejectsShortHmacKey() {
        var shortHex = "0123456789abcdef";
        assertThatThrownBy(() -> AuditConfiguration.resolveKey(
                new AuditHmacProperties(shortHex),
                "enterprise",
                null,
                messageSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hex string of at least 32 bytes");
    }

    @Test
    void rejectsNonHexKey() {
        assertThatThrownBy(() -> AuditConfiguration.resolveKey(
                new AuditHmacProperties("zzzz"),
                "community",
                SAMPLE_ENCRYPTION_KEY,
                messageSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hex string");
    }

    @Test
    void failsOnNonCommunityWithoutKey() {
        assertThatThrownBy(() -> AuditConfiguration.resolveKey(
                new AuditHmacProperties(null),
                "enterprise",
                SAMPLE_ENCRYPTION_KEY,
                messageSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required for non-community editions");
    }

    @Test
    void failsOnCommunityWithoutEncryptionKey() {
        assertThatThrownBy(() -> AuditConfiguration.resolveKey(
                new AuditHmacProperties(""),
                "community",
                "",
                messageSource))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required");
    }

    @Test
    void derivesKeyFromEncryptionKeyForCommunity() {
        var key = AuditConfiguration.resolveKey(
                new AuditHmacProperties(null),
                "community",
                SAMPLE_ENCRYPTION_KEY,
                messageSource);
        assertThat(key).hasSize(32);
        // Determinism: same encryption key → same derived audit key.
        var again = AuditConfiguration.resolveKey(
                new AuditHmacProperties(""),
                "community",
                SAMPLE_ENCRYPTION_KEY,
                messageSource);
        assertThat(again).isEqualTo(key);
    }

    @Test
    void derivedKeyDiffersFromEncryptionKey() {
        var encBytes = java.util.HexFormat.of().parseHex(SAMPLE_ENCRYPTION_KEY);
        var audit = AuditConfiguration.resolveKey(
                new AuditHmacProperties(null),
                "community",
                SAMPLE_ENCRYPTION_KEY,
                messageSource);
        assertThat(audit).isNotEqualTo(encBytes);
    }

    @Test
    void derivedKeyDiffersBetweenEncryptionKeys() {
        var first = AuditConfiguration.resolveKey(
                new AuditHmacProperties(null),
                "community",
                SAMPLE_ENCRYPTION_KEY,
                messageSource);
        var second = AuditConfiguration.resolveKey(
                new AuditHmacProperties(null),
                "community",
                "ababababababababababababababababababababababababababababababab12",
                messageSource);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void treatsMissingEditionAsCommunity() {
        var key = AuditConfiguration.resolveKey(
                new AuditHmacProperties(null),
                null,
                SAMPLE_ENCRYPTION_KEY,
                messageSource);
        assertThat(key).hasSize(32);
    }

    @Test
    void hkdfOutputLengthMatchesRequest() {
        var ikm = new byte[]{1, 2, 3, 4, 5};
        var info = "test-info".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(AuditConfiguration.hkdfSha256(ikm, info, 16)).hasSize(16);
        assertThat(AuditConfiguration.hkdfSha256(ikm, info, 32)).hasSize(32);
        assertThat(AuditConfiguration.hkdfSha256(ikm, info, 64)).hasSize(64);
    }

    private static MessageSource createMessageSource() {
        var source = new ResourceBundleMessageSource();
        source.setBasename("i18n/messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
