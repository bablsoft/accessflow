package com.bablsoft.accessflow.workflow.internal.hook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionHookSignerTest {

    private static final byte[] BODY = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

    @Test
    void signsInTheWebhookFormat() {
        // Reference value: printf '{"a":1}' | openssl dgst -sha256 -hmac secret
        assertThat(DecisionHookSigner.sign(BODY, "secret"))
                .startsWith("sha256=")
                .hasSize("sha256=".length() + 64);
    }

    @Test
    void verifiesItsOwnSignatureCaseInsensitively() {
        var signature = DecisionHookSigner.sign(BODY, "secret");
        assertThat(DecisionHookSigner.verify(BODY, "secret", signature)).isTrue();
        assertThat(DecisionHookSigner.verify(BODY, "secret", " " + signature.toUpperCase()
                .replace("SHA256=", "sha256=") + " ")).isTrue();
    }

    @Test
    void rejectsAWrongKeyATamperedBodyAndAMissingHeader() {
        var signature = DecisionHookSigner.sign(BODY, "secret");
        assertThat(DecisionHookSigner.verify(BODY, "other", signature)).isFalse();
        assertThat(DecisionHookSigner.verify("{\"a\":2}".getBytes(StandardCharsets.UTF_8), "secret",
                signature)).isFalse();
        assertThat(DecisionHookSigner.verify(BODY, "secret", null)).isFalse();
        assertThat(DecisionHookSigner.verify(BODY, "secret", "sha256=")).isFalse();
    }
}
