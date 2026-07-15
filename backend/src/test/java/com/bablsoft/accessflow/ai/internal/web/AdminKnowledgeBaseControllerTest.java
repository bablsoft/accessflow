package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.ai.api.CreateKnowledgeDocumentCommand;
import com.bablsoft.accessflow.ai.api.KnowledgeBaseService;
import com.bablsoft.accessflow.ai.api.KnowledgeDocumentView;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminKnowledgeBaseControllerTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID configId = UUID.randomUUID();
    private final UUID docId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("203.0.113.5", "ua/1");
    private final Authentication authentication = new UsernamePasswordAuthenticationToken(
            JwtClaims.forSystemRole(userId, "admin@example.com", UserRoleType.ADMIN, organizationId),
            "n/a", List.of());

    private KnowledgeBaseService knowledgeBaseService;
    private AuditLogService auditLogService;
    private AdminKnowledgeBaseController controller;

    @BeforeEach
    void setUp() {
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new AdminKnowledgeBaseController(knowledgeBaseService, auditLogService);
        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/admin/ai-configs/" + configId + "/knowledge-documents");
        request.setServerName("localhost");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private KnowledgeDocumentView view() {
        return new KnowledgeDocumentView(docId, configId, "Policy", 20, 2,
                "INDEXED", null, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void listMapsResponses() {
        when(knowledgeBaseService.list(configId, organizationId)).thenReturn(List.of(view()));

        var list = controller.list(configId, authentication);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).title()).isEqualTo("Policy");
    }

    @Test
    void createDelegatesAndAudits() {
        when(knowledgeBaseService.create(any(), any(), any(CreateKnowledgeDocumentCommand.class)))
                .thenReturn(view());

        var response = controller.create(configId,
                new CreateKnowledgeDocumentRequest("Policy", "Never expose PII."),
                authentication, auditContext);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().title()).isEqualTo("Policy");
        verify(auditLogService).record(argMatches(AuditAction.KNOWLEDGE_DOCUMENT_CREATED));
    }

    @Test
    void deleteDelegatesAndAudits() {
        var response = controller.delete(configId, docId, authentication, auditContext);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(knowledgeBaseService).delete(docId, configId, organizationId);
        verify(auditLogService).record(argMatches(AuditAction.KNOWLEDGE_DOCUMENT_DELETED));
    }

    private static AuditEntry argMatches(AuditAction action) {
        return org.mockito.ArgumentMatchers.argThat(e -> e != null && e.action() == action);
    }
}
