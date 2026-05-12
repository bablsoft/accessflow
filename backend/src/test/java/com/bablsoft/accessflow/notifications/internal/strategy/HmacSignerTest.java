package com.bablsoft.accessflow.notifications.internal.strategy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacSignerTest {

    @Test
    void matchesKnownVector() {
        // Cross-checked with: python -c 'import hmac, hashlib; print(hmac.new(b"shh", b"hello", hashlib.sha256).hexdigest())'
        var hex = HmacSigner.sha256Hex("hello".getBytes(StandardCharsets.UTF_8), "shh");
        assertThat(hex).isEqualTo(
                "0e396369ee043c5b6b922743631745b2249cf7cb2c4722e61e802447d5d14c70");
    }

    @Test
    void differentSecretsProduceDifferentHashes() {
        var a = HmacSigner.sha256Hex("payload".getBytes(StandardCharsets.UTF_8), "k1");
        var b = HmacSigner.sha256Hex("payload".getBytes(StandardCharsets.UTF_8), "k2");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void rejectsNullBody() {
        assertThatThrownBy(() -> HmacSigner.sha256Hex(null, "k"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullSecret() {
        assertThatThrownBy(() -> HmacSigner.sha256Hex(new byte[0], null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
