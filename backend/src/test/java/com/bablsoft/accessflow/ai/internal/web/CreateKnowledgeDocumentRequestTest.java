package com.bablsoft.accessflow.ai.internal.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreateKnowledgeDocumentRequestTest {

    @Test
    void mapsToCommand() {
        var command = new CreateKnowledgeDocumentRequest("Policy", "Never expose PII.").toCommand();

        assertThat(command.title()).isEqualTo("Policy");
        assertThat(command.content()).isEqualTo("Never expose PII.");
    }
}
