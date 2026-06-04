package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.LangfuseConfigView;
import com.bablsoft.accessflow.ai.api.LangfuseConnectionTestResult;
import com.bablsoft.accessflow.ai.api.UpdateLangfuseConfigCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseConfigWebMappingTest {

    private static LangfuseConfigView view(boolean secretConfigured) {
        return new LangfuseConfigView(UUID.randomUUID(), UUID.randomUUID(), true,
                "https://lf.example.com/", "pk-lf-1", secretConfigured, true, false,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"));
    }

    @Test
    void responseMasksSecretWhenConfigured() {
        var response = LangfuseConfigResponse.from(view(true));

        assertThat(response.secretKey()).isEqualTo(UpdateLangfuseConfigCommand.MASKED_SECRET);
        assertThat(response.publicKey()).isEqualTo("pk-lf-1");
        assertThat(response.enabled()).isTrue();
        assertThat(response.tracingEnabled()).isTrue();
        assertThat(response.promptManagementEnabled()).isFalse();
    }

    @Test
    void responseReturnsNullSecretWhenNotConfigured() {
        var response = LangfuseConfigResponse.from(view(false));

        assertThat(response.secretKey()).isNull();
    }

    @Test
    void requestMapsToCommandVerbatim() {
        var request = new UpdateLangfuseConfigRequest(true, "https://lf.example.com",
                "pk-lf-1", "sk-lf-1", false, true);

        var command = request.toCommand();

        assertThat(command.enabled()).isTrue();
        assertThat(command.host()).isEqualTo("https://lf.example.com");
        assertThat(command.publicKey()).isEqualTo("pk-lf-1");
        assertThat(command.secretKey()).isEqualTo("sk-lf-1");
        assertThat(command.tracingEnabled()).isFalse();
        assertThat(command.promptManagementEnabled()).isTrue();
    }

    @Test
    void testResponseMapsStatus() {
        assertThat(LangfuseConfigTestResponse.from(new LangfuseConnectionTestResult(true, "ok")).status())
                .isEqualTo("OK");
        assertThat(LangfuseConfigTestResponse.from(new LangfuseConnectionTestResult(false, "boom")).status())
                .isEqualTo("ERROR");
        assertThat(LangfuseConfigTestResponse.from(new LangfuseConnectionTestResult(false, "boom")).message())
                .isEqualTo("boom");
    }
}
