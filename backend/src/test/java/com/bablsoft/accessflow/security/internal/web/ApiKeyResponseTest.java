package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.security.api.ApiKeyView;
import com.bablsoft.accessflow.security.api.IssuedApiKey;
import com.bablsoft.accessflow.security.internal.web.model.ApiKeyCreateResponse;
import com.bablsoft.accessflow.security.internal.web.model.ApiKeyResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyResponseTest {

    @Test
    void from_view_copies_all_safe_fields() {
        var view = sampleView();
        var response = ApiKeyResponse.from(view);
        assertThat(response.id()).isEqualTo(view.id());
        assertThat(response.name()).isEqualTo(view.name());
        assertThat(response.keyPrefix()).isEqualTo(view.keyPrefix());
        assertThat(response.createdAt()).isEqualTo(view.createdAt());
        assertThat(response.lastUsedAt()).isEqualTo(view.lastUsedAt());
        assertThat(response.expiresAt()).isEqualTo(view.expiresAt());
        assertThat(response.revokedAt()).isEqualTo(view.revokedAt());
    }

    @Test
    void create_response_carries_raw_key_alongside_view() {
        var view = sampleView();
        var issued = new IssuedApiKey(view, "af_secret-once");
        var response = ApiKeyCreateResponse.from(issued);
        assertThat(response.rawKey()).isEqualTo("af_secret-once");
        assertThat(response.apiKey().id()).isEqualTo(view.id());
    }

    private ApiKeyView sampleView() {
        return new ApiKeyView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "ci", "af_demoxxxxx",
                Instant.parse("2026-05-10T00:00:00Z"),
                Instant.parse("2026-05-11T00:00:00Z"),
                Instant.parse("2026-12-01T00:00:00Z"),
                null);
    }
}
