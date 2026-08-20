package com.bablsoft.accessflow.audit.internal.sink;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SinkHmacSignerTest {

    /** RFC 2202-style known vector: HMAC-SHA256("key", "The quick brown fox ..."). */
    @Test
    void computesKnownVector() {
        var body = "The quick brown fox jumps over the lazy dog"
                .getBytes(StandardCharsets.UTF_8);

        assertThat(SinkHmacSigner.sha256Hex(body, "key"))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void producesLowercaseHexOfExpectedLength() {
        var hex = SinkHmacSigner.sha256Hex(new byte[0], "secret");

        assertThat(hex).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void nullBodyThrows() {
        assertThatThrownBy(() -> SinkHmacSigner.sha256Hex(null, "secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("body");
    }

    @Test
    void nullSecretThrows() {
        assertThatThrownBy(() -> SinkHmacSigner.sha256Hex(new byte[0], null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret");
    }
}
