package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.workflow.api.ConditionNode;
import com.bablsoft.accessflow.workflow.api.CreateRoutingPolicyCommand;
import com.bablsoft.accessflow.workflow.api.RoutingAction;
import com.bablsoft.accessflow.workflow.api.RoutingPolicyService;
import com.bablsoft.accessflow.workflow.api.RoutingPolicyView;
import com.bablsoft.accessflow.workflow.api.UpdateRoutingPolicyCommand;
import com.bablsoft.accessflow.workflow.internal.routing.RoutingConditionCodec;
import com.bablsoft.accessflow.workflow.internal.web.model.CreateRoutingPolicyRequest;
import com.bablsoft.accessflow.workflow.internal.web.model.ReorderRoutingPoliciesRequest;
import com.bablsoft.accessflow.workflow.internal.web.model.UpdateRoutingPolicyRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminRoutingPolicyControllerTest {

    private RoutingPolicyService service;
    private AuditLogService auditLogService;
    private AdminRoutingPolicyController controller;
    private final RoutingConditionCodec codec = new RoutingConditionCodec(
            JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build(),
            new StaticMessageSource());

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID policyId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("203.0.113.5", "ua/1");
    private final Authentication authentication = new UsernamePasswordAuthenticationToken(
            new JwtClaims(userId, "admin@x.com", UserRoleType.ADMIN, organizationId), "n/a",
            List.of());
    private final ConditionNode condition = new ConditionNode.QueryTypeIn(Set.of(QueryType.DELETE));

    @BeforeEach
    void setUp() {
        service = mock(RoutingPolicyService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new AdminRoutingPolicyController(service, codec, auditLogService);
        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/admin/routing-policies");
        request.setServerName("localhost");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private RoutingPolicyView view(String name) {
        return new RoutingPolicyView(policyId, organizationId, null, name, null, 1, true, condition,
                RoutingAction.AUTO_REJECT, null, "blocked", 0L, Instant.now(), Instant.now());
    }

    @Test
    void listMapsConditionToJson() {
        when(service.list(organizationId)).thenReturn(List.of(view("Block")));

        var result = controller.list(authentication);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Block");
        assertThat(result.get(0).condition().get("type").asString()).isEqualTo("query_type");
    }

    @Test
    void getMaps() {
        when(service.get(policyId, organizationId)).thenReturn(view("Block"));

        assertThat(controller.get(policyId, authentication).name()).isEqualTo("Block");
    }

    @Test
    void createDelegatesAndAudits() {
        when(service.create(any(CreateRoutingPolicyCommand.class))).thenReturn(view("Block"));
        var request = new CreateRoutingPolicyRequest("Block", null, null, 1, true,
                codec.toJson(condition), RoutingAction.AUTO_REJECT, null, "blocked");

        var response = controller.create(request, authentication, auditContext);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void updateDelegatesAndAudits() {
        when(service.update(eq(policyId), eq(organizationId), any(UpdateRoutingPolicyCommand.class)))
                .thenReturn(view("Renamed"));
        var request = new UpdateRoutingPolicyRequest("Renamed", null, null, 1, true,
                codec.toJson(condition), RoutingAction.AUTO_REJECT, null, null);

        var response = controller.update(policyId, request, authentication, auditContext);

        assertThat(response.name()).isEqualTo("Renamed");
        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void deleteReturns204AndAudits() {
        var response = controller.delete(policyId, authentication, auditContext);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(service).delete(policyId, organizationId);
        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void reorderDelegatesAndAudits() {
        when(service.reorder(eq(organizationId), any())).thenReturn(List.of(view("Block")));

        var result = controller.reorder(
                new ReorderRoutingPoliciesRequest(List.of(policyId)), authentication, auditContext);

        assertThat(result).hasSize(1);
        verify(auditLogService).record(any(AuditEntry.class));
    }
}
