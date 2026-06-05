package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.CreateKnowledgeDocumentCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record CreateKnowledgeDocumentRequest(
        @NotBlank(message = "{validation.knowledge_document.title.required}")
        @Size(min = 1, max = 255, message = "{validation.knowledge_document.title.size}") String title,
        @NotBlank(message = "{validation.knowledge_document.content.required}")
        @Size(max = 100000, message = "{validation.knowledge_document.content.size}") String content) {

    CreateKnowledgeDocumentCommand toCommand() {
        return new CreateKnowledgeDocumentCommand(title, content);
    }
}
