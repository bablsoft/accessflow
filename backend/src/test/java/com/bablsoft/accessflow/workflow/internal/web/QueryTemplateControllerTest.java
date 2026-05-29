package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.QueryTemplateFilter;
import com.bablsoft.accessflow.workflow.api.QueryTemplateService;
import com.bablsoft.accessflow.workflow.api.QueryTemplateView;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;
import com.bablsoft.accessflow.workflow.internal.web.model.CreateQueryTemplateRequest;
import com.bablsoft.accessflow.workflow.internal.web.model.UpdateQueryTemplateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryTemplateControllerTest {

    private QueryTemplateService queryTemplateService;
    private AuditLogService auditLogService;
    private QueryTemplateController controller;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("203.0.113.5", "ua/1");
    private final Authentication authentication = new UsernamePasswordAuthenticationToken(
            new JwtClaims(userId, "alice@x.com", UserRoleType.ANALYST, organizationId),
            "n/a", List.of());

    @BeforeEach
    void setUp() {
        queryTemplateService = mock(QueryTemplateService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new QueryTemplateController(queryTemplateService, auditLogService);
        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/query-templates");
        request.setServerName("localhost");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void listDelegatesToServiceAndMapsContent() {
        var view = view(userId, "Top", QueryTemplateVisibility.PRIVATE);
        when(queryTemplateService.list(eq(organizationId), eq(userId),
                any(QueryTemplateFilter.class), any()))
                .thenReturn(new PageResponse<>(List.of(view), 0, 20, 1L, 1));

        var page = controller.list(null, null, null, null, authentication, PageRequest.of(0, 20));

        assertThat(page.totalElements()).isEqualTo(1L);
        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).editable()).isTrue();
        assertThat(page.content().get(0).name()).isEqualTo("Top");
    }

    @Test
    void createDelegatesToServiceAndRecordsAudit() {
        var request = new CreateQueryTemplateRequest("Top", "SELECT 1", "desc",
                List.of("a"), null, QueryTemplateVisibility.TEAM);
        var created = view(userId, "Top", QueryTemplateVisibility.TEAM);
        when(queryTemplateService.create(any(QueryTemplateService.CreateQueryTemplateCommand.class)))
                .thenReturn(created);

        var response = controller.create(request, authentication, auditContext);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().editable()).isTrue();
        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void getDelegatesAndReturnsResponse() {
        var view = view(userId, "Top", QueryTemplateVisibility.PRIVATE);
        when(queryTemplateService.get(templateId, organizationId, userId)).thenReturn(view);

        var response = controller.get(templateId, authentication);

        assertThat(response.name()).isEqualTo("Top");
        assertThat(response.editable()).isTrue();
    }

    @Test
    void updateDelegatesAndRecordsAudit() {
        var request = new UpdateQueryTemplateRequest("Renamed", "SELECT 2", "desc",
                List.of(), null, QueryTemplateVisibility.PRIVATE);
        var updated = view(userId, "Renamed", QueryTemplateVisibility.PRIVATE);
        when(queryTemplateService.update(eq(templateId), eq(organizationId), eq(userId),
                any(QueryTemplateService.UpdateQueryTemplateCommand.class)))
                .thenReturn(updated);

        var response = controller.update(templateId, request, authentication, auditContext);

        assertThat(response.name()).isEqualTo("Renamed");
        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void deleteReturns204AndRecordsAudit() {
        var response = controller.delete(templateId, authentication, auditContext);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(queryTemplateService).delete(templateId, organizationId, userId);
        verify(auditLogService).record(any(AuditEntry.class));
    }

    private QueryTemplateView view(UUID owner, String name, QueryTemplateVisibility visibility) {
        return new QueryTemplateView(
                templateId, organizationId, owner, "Alice",
                null, name, "SELECT 1", "desc",
                List.of("a"), visibility,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"));
    }
}
