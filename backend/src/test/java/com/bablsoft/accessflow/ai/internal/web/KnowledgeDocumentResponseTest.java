package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.KnowledgeDocumentView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeDocumentResponseTest {

    @Test
    void mapsAllFieldsFromView() {
        var id = UUID.randomUUID();
        var configId = UUID.randomUUID();
        var created = Instant.parse("2026-01-01T00:00:00Z");
        var view = new KnowledgeDocumentView(id, configId, "Policy", 42, 3, "INDEXED",
                null, created, created);

        var response = KnowledgeDocumentResponse.from(view);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.aiConfigId()).isEqualTo(configId);
        assertThat(response.title()).isEqualTo("Policy");
        assertThat(response.charCount()).isEqualTo(42);
        assertThat(response.chunkCount()).isEqualTo(3);
        assertThat(response.status()).isEqualTo("INDEXED");
        assertThat(response.errorMessage()).isNull();
        assertThat(response.createdAt()).isEqualTo(created);
    }
}
